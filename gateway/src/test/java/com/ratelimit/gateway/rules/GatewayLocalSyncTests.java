package com.ratelimit.gateway.rules;

import static com.ratelimit.gateway.rules.TestSnapshots.key;
import static com.ratelimit.gateway.rules.TestSnapshots.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.proxy.StubUpstream;
import com.ratelimit.gateway.ratelimit.sync.NodeRegistry;
import com.ratelimit.gateway.redis.LateRedis;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.ApiKeyHash;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.TokenBucketParams;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code LOCAL_SYNC} rules in running gateways: real HTTP requests, a real Redis and published rules. */
@Testcontainers
class GatewayLocalSyncTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String API_KEY = "local-sync-test-key";
    private static final long RULE_ID = 10;
    private static final double NO_REFILL = 0.000001;

    /** Replaces the built-in per-IP rule, whose 100 tokens these tests would run into. */
    private static final Rule OPEN_IP = new Rule(99, "ip-open", LimitBy.IP, null, null, "/**", null,
            Algorithm.TOKEN_BUCKET, Mode.STRICT, FailureMode.LOCAL_FALLBACK,
            new TokenBucketParams(1_000_000, 1_000_000), 0, 1);

    static RedisClient client;
    static StatefulRedisConnection<String, String> redis;
    static StubUpstream upstream;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ConfigurableApplicationContext> gateways = new ArrayList<>();

    @TempDir
    Path dir;

    @BeforeAll
    static void connect() throws IOException {
        client = RedisClient.create(redisUrl());
        redis = client.connect();
        upstream = new StubUpstream("images");
    }

    @AfterAll
    static void disconnect() {
        upstream.close();
        redis.close();
        client.shutdown();
    }

    @BeforeEach
    void emptyRedis() {
        redis.sync().flushall();
    }

    @AfterEach
    void stopGateways() {
        gateways.forEach(ConfigurableApplicationContext::close);
    }

    private static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static Rule images(Mode mode, long capacity, double refillPerSec) {
        return new Rule(RULE_ID, "images", LimitBy.USER, null, null, "/api/images/**", null, Algorithm.TOKEN_BUCKET,
                mode, FailureMode.LOCAL_FALLBACK, new TokenBucketParams(capacity, refillPerSec), 0, 1);
    }

    private static void publish(long version, Rule images) {
        String text = snapshot(version, List.of(key(API_KEY, "alice", "free")), OPEN_IP, images);
        redis.sync().hset(SnapshotKeys.KEY, Map.of(SnapshotKeys.FIELD_VERSION, Long.toString(version),
                SnapshotKeys.FIELD_SNAPSHOT, text));
        redis.sync().publish(SnapshotKeys.CHANNEL, Long.toString(version));
    }

    private ConfigurableApplicationContext startGateway(String url, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--gateway.redis.url=" + url,
                "--gateway.redis.command-timeout=500ms",
                "--gateway.ratelimit.snapshot-file=" + dir.resolve("saved-" + gateways.size() + ".json"),
                "--gateway.proxy.routes[0].pattern=/api/images/**",
                "--gateway.proxy.routes[0].service=images",
                "--gateway.proxy.routes[0].instances[0]=" + upstream.url()));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext gateway =
                new SpringApplicationBuilder(GatewayApplication.class).run(args.toArray(String[]::new));
        gateways.add(gateway);
        return gateway;
    }

    /** Three gateways that follow published rules and know of each other. */
    private List<ConfigurableApplicationContext> threeGateways(String... extra) {
        var three = List.of(startGateway(redisUrl(), extra), startGateway(redisUrl(), extra),
                startGateway(redisUrl(), extra));
        await().atMost(Duration.ofSeconds(5)).until(
                () -> three.stream().allMatch(g -> g.getBean(NodeRegistry.class).nodeCount() == 3));
        await().atMost(Duration.ofSeconds(5)).until(
                () -> redis.sync().pubsubNumsub(SnapshotKeys.CHANNEL).get(SnapshotKeys.CHANNEL) == 3);
        return three;
    }

    private static void awaitVersion(List<ConfigurableApplicationContext> all, long version) {
        await().atMost(Duration.ofSeconds(2)).until(() -> all.stream()
                .allMatch(g -> g.getBean(LiveRuleResolver.class).current().version() == version));
    }

    private int status(ConfigurableApplicationContext gateway) {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/images/a"))
                .header("X-API-Key", API_KEY).timeout(Duration.ofSeconds(5)).build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            assertThat(status).isIn(200, 429);
            return status;
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Sends {@code requests} requests, each to the next gateway in turn; returns how many were allowed. */
    private long send(int requests, List<ConfigurableApplicationContext> to) {
        long allowed = 0;
        for (int i = 0; i < requests; i++) {
            if (status(to.get(i % to.size())) == 200) {
                allowed++;
            }
        }
        return allowed;
    }

    private static double sharedTokens() {
        return Double.parseDouble(redis.sync().hget("rl:{" + RULE_ID + ":alice}:tb", "tokens"));
    }

    // ---- one limit for the cluster ----

    @Test
    void threeGatewaysShareOneLimit() {
        publish(1, images(Mode.LOCAL_SYNC, 60, NO_REFILL));
        var three = threeGateways();

        // counted separately, as before, the three would allow 180
        assertThat(send(300, three)).isEqualTo(60);
        assertThat(sharedTokens()).isLessThan(1);
    }

    @Test
    void trafficThroughOnlyOneOfThreeGatewaysStillGetsTheWholeLimit() {
        publish(1, images(Mode.LOCAL_SYNC, 60, NO_REFILL));
        var three = threeGateways();

        assertThat(send(200, three.subList(0, 1))).isEqualTo(60);
    }

    // ---- rule changes ----

    @Test
    void aLoweredLimitAlsoCoversTokensTheGatewaysHadAlreadyBorrowed() {
        publish(1, images(Mode.LOCAL_SYNC, 300, NO_REFILL));
        var three = threeGateways("--gateway.lease.min-batch=50", "--gateway.lease.horizon=30s");
        assertThat(send(3, three)).isEqualTo(3);
        assertThat(sharedTokens()).as("each gateway is holding about 49 tokens").isLessThan(151);

        publish(2, images(Mode.LOCAL_SYNC, 5, NO_REFILL));
        awaitVersion(three, 2);

        assertThat(send(90, three)).isEqualTo(5);
    }

    @Test
    void aRaisedRefillRateAppliesAtOnceToAKeyThatWasOutOfTokens() throws InterruptedException {
        // with a 30 s horizon, "no tokens" would otherwise be believed for up to 30 s
        publish(1, images(Mode.LOCAL_SYNC, 3, NO_REFILL));
        var three = threeGateways("--gateway.lease.horizon=30s");
        assertThat(send(30, three)).isEqualTo(3);

        publish(2, images(Mode.LOCAL_SYNC, 3, 1_000));
        awaitVersion(three, 2);
        Thread.sleep(50);

        for (var gateway : three) {
            assertThat(status(gateway)).isEqualTo(200);
        }
    }

    @Test
    void switchingBetweenTheModesKeepsWhatWasAlreadySpent() {
        publish(1, images(Mode.STRICT, 10, NO_REFILL));
        var three = threeGateways();
        assertThat(send(4, three)).isEqualTo(4);

        publish(2, images(Mode.LOCAL_SYNC, 10, NO_REFILL));
        awaitVersion(three, 2);
        assertThat(send(3, three)).isEqualTo(3);

        publish(3, images(Mode.STRICT, 10, NO_REFILL));
        awaitVersion(three, 3);
        long afterwards = send(30, three);

        // at most the 3 left; fewer if a gateway still held some, which STRICT cannot see
        assertThat(afterwards).isLessThanOrEqualTo(3);
        assertThat(sharedTokens()).isLessThan(1);
    }

    // ---- Redis outage ----

    @Test
    void duringAnOutageEachGatewayLimitsByItselfAndSharingResumesAfterwards() {
        try (var late = new LateRedis()) {
            late.start();
            String prefix = "--gateway.ratelimit.rules[0].";
            var gateway = startGateway(late.url(),
                    "--gateway.lease.horizon=200ms",
                    "--gateway.auth.api-keys[0].key-hash=" + ApiKeyHash.of(API_KEY),
                    "--gateway.auth.api-keys[0].user-id=alice",
                    "--gateway.auth.api-keys[0].tier=free",
                    prefix + "id=" + RULE_ID, prefix + "name=images", prefix + "limit-by=USER",
                    prefix + "endpoint-pattern=/api/images/**", prefix + "algorithm=TOKEN_BUCKET",
                    prefix + "mode=LOCAL_SYNC", prefix + "params.capacity=10", prefix + "params.refill-per-sec=0.000001");
            assertThat(status(gateway)).isEqualTo(200);

            late.stopServer();
            long duringOutage = send(40, List.of(gateway));

            // the node's own bucket of 10, plus at most what it still held; not unlimited, and not nothing
            assertThat(duringOutage).isBetween(10L, 19L);
            assertThat(status(gateway)).isEqualTo(429);

            late.startServerAgain();
            // the node's own bucket is spent, so only tokens borrowed from Redis again can allow a request
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .until(() -> status(gateway) == 200);
        }
    }
}
