package com.ratelimit.gateway.rules;

import static com.ratelimit.gateway.rules.TestSnapshots.key;
import static com.ratelimit.gateway.rules.TestSnapshots.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.proxy.StubUpstream;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.ApiKeyHash;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.TokenBucketParams;
import com.ratelimit.rules.snapshot.ConfigSnapshot.ApiKeyEntry;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
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

/** API keys as a running gateway sees them: real HTTP requests, a real Redis and a real upstream. */
@Testcontainers
class GatewayApiKeyTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String NO_REDIS = "redis://127.0.0.1:1";
    private static final String CONFIGURED_KEY = "key-listed-in-yaml";
    private static final String ALICE_KEY = "key-created-by-an-admin";

    private static final Rule FREE_LIMIT = tierRule(1, "free-limit", "free", 50);
    private static final Rule PRO_LIMIT = tierRule(2, "pro-limit", "pro", 500);

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

    private static Rule tierRule(long id, String name, String tier, long capacity) {
        return new Rule(id, name, LimitBy.USER, tier, null, "/api/images/**", null, Algorithm.TOKEN_BUCKET, Mode.STRICT,
                FailureMode.LOCAL_FALLBACK, new TokenBucketParams(capacity, 1), 0, 1);
    }

    private static String config(long version, ApiKeyEntry... keys) {
        return snapshot(version, List.of(keys), FREE_LIMIT, PRO_LIMIT);
    }

    private static void publish(long version, ApiKeyEntry... keys) {
        redis.sync().hset(SnapshotKeys.KEY, Map.of(SnapshotKeys.FIELD_VERSION, Long.toString(version),
                SnapshotKeys.FIELD_SNAPSHOT, config(version, keys)));
        redis.sync().publish(SnapshotKeys.CHANNEL, Long.toString(version));
    }

    private Path savedCopy() {
        return dir.resolve("saved.json");
    }

    /** Starts a gateway that also has one key in its own configuration, as a deployment might. */
    private ConfigurableApplicationContext startGateway(String url) {
        ConfigurableApplicationContext gateway = new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.port=0",
                "--gateway.redis.url=" + url,
                "--gateway.redis.command-timeout=200ms",
                "--gateway.ratelimit.snapshot-file=" + savedCopy(),
                "--gateway.ratelimit.update-poll-interval=1h", // changes arrive by announcement only
                "--gateway.auth.api-keys[0].key-hash=" + ApiKeyHash.of(CONFIGURED_KEY),
                "--gateway.auth.api-keys[0].user-id=yaml-user",
                "--gateway.auth.api-keys[0].tier=free",
                "--gateway.proxy.routes[0].pattern=/api/images/**",
                "--gateway.proxy.routes[0].service=images",
                "--gateway.proxy.routes[0].instances[0]=" + upstream.url());
        gateways.add(gateway);
        return gateway;
    }

    private static void awaitSubscriber() {
        await().atMost(Duration.ofSeconds(5)).until(
                () -> redis.sync().pubsubNumsub(SnapshotKeys.CHANNEL).get(SnapshotKeys.CHANNEL) == 1);
    }

    private HttpResponse<String> call(ConfigurableApplicationContext gateway, String apiKey) {
        int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/images/a"))
                .header("X-API-Key", apiKey).timeout(Duration.ofSeconds(5)).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private int status(ConfigurableApplicationContext gateway, String apiKey) {
        return call(gateway, apiKey).statusCode();
    }

    private static String policy(HttpResponse<String> response) {
        return response.headers().firstValue("X-RateLimit-Policy").orElse(null);
    }

    // ---- published keys ----

    @Test
    void aPublishedKeyAuthenticatesWithItsUserAndTier() {
        publish(2, key(ALICE_KEY, "alice", "pro"));
        var gateway = startGateway(redisUrl());

        HttpResponse<String> response = call(gateway, ALICE_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(policy(response)).isEqualTo("pro-limit");
        assertThat(upstream.lastHeaders().get("x-user-id")).containsExactly("alice");
        assertThat(upstream.lastHeaders().get("x-tier")).containsExactly("pro");
        assertThat(upstream.lastHeaders()).doesNotContainKey("x-api-key");
    }

    @Test
    void aKeyListedOnlyInTheGatewaysOwnConfigurationStopsWorkingOnceConfigurationIsPublished() {
        publish(2, key(ALICE_KEY, "alice", "pro"));
        var gateway = startGateway(redisUrl());

        assertThat(status(gateway, CONFIGURED_KEY)).isEqualTo(401);
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(200);
    }

    // ---- while running ----

    @Test
    void aNewKeyWorksWithoutARestart() {
        publish(1);
        var gateway = startGateway(redisUrl());
        awaitSubscriber();
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(401);

        publish(2, key(ALICE_KEY, "alice", "free"));

        await().atMost(Duration.ofSeconds(2)).until(() -> status(gateway, ALICE_KEY) == 200);
    }

    @Test
    void aRevokedKeyStopsWorkingWithinTwoSeconds() {
        publish(1, key(ALICE_KEY, "alice", "free"));
        var gateway = startGateway(redisUrl());
        awaitSubscriber();
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(200);

        publish(2); // revoked: simply no longer there

        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50))
                .until(() -> status(gateway, ALICE_KEY) == 401);
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(401);
    }

    @Test
    void aChangeOfTierAppliesToTheNextRequest() {
        publish(1, key(ALICE_KEY, "alice", "free"));
        var gateway = startGateway(redisUrl());
        awaitSubscriber();
        assertThat(policy(call(gateway, ALICE_KEY))).isEqualTo("free-limit");

        publish(2, key(ALICE_KEY, "alice", "pro"));

        await().atMost(Duration.ofSeconds(2)).until(() -> "pro-limit".equals(policy(call(gateway, ALICE_KEY))));
    }

    @Test
    void configuredKeysWorkUntilTheFirstPublishedConfigurationArrives() {
        var gateway = startGateway(redisUrl()); // nothing published yet
        awaitSubscriber();
        assertThat(status(gateway, CONFIGURED_KEY)).isEqualTo(200);

        publish(2, key(ALICE_KEY, "alice", "free"));

        await().atMost(Duration.ofSeconds(2)).until(() -> status(gateway, CONFIGURED_KEY) == 401);
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(200);
    }

    @Test
    void anUnusableSnapshotOrAnEmptiedRedisLeavesTheKeysAsTheyWere() throws Exception {
        publish(1, key(ALICE_KEY, "alice", "free"));
        var gateway = startGateway(redisUrl());
        awaitSubscriber();

        redis.sync().hset(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT, "garbage");
        redis.sync().publish(SnapshotKeys.CHANNEL, "2");
        Thread.sleep(500);
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(200);

        redis.sync().flushall();
        redis.sync().publish(SnapshotKeys.CHANNEL, "3");
        Thread.sleep(500);
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(200);
        assertThat(status(gateway, CONFIGURED_KEY)).isEqualTo(401);
    }

    // ---- starting without Redis ----

    @Test
    void withRedisDownTheSavedCopysKeysApply() throws IOException {
        Files.writeString(savedCopy(), config(4, key(ALICE_KEY, "alice", "pro")));

        var gateway = startGateway(NO_REDIS);

        assertThat(status(gateway, ALICE_KEY)).isEqualTo(200);
        assertThat(status(gateway, CONFIGURED_KEY)).isEqualTo(401);
    }

    @Test
    void withRedisDownAndNoSavedCopyOnlyTheConfiguredKeysApply() {
        var gateway = startGateway(NO_REDIS);

        assertThat(status(gateway, CONFIGURED_KEY)).isEqualTo(200);
        assertThat(status(gateway, ALICE_KEY)).isEqualTo(401);
    }

    @Test
    void theSavedCopyHoldsHashesNeverKeys() throws IOException {
        publish(2, key(ALICE_KEY, "alice", "pro"));

        startGateway(redisUrl());

        assertThat(Files.readString(savedCopy())).contains(ApiKeyHash.of(ALICE_KEY)).doesNotContain(ALICE_KEY);
    }
}
