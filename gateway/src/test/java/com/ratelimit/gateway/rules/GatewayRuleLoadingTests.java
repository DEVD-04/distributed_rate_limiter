package com.ratelimit.gateway.rules;

import static com.ratelimit.gateway.rules.TestSnapshots.images;
import static com.ratelimit.gateway.rules.TestSnapshots.localSyncLeakyBucket;
import static com.ratelimit.gateway.rules.TestSnapshots.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.gateway.redis.LateRedis;
import com.ratelimit.gateway.redis.RedisConnector;
import com.ratelimit.gateway.rules.RuleSnapshotLoader.Refresh;
import com.ratelimit.rules.ResolvedRule;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The whole gateway being started in different situations, with a real Redis. */
@Testcontainers
class GatewayRuleLoadingTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Nothing listens here, so connections are refused. */
    private static final String NO_REDIS = "redis://127.0.0.1:1";

    static RedisClient client;
    static StatefulRedisConnection<String, String> redis;

    private final List<ConfigurableApplicationContext> gateways = new ArrayList<>();

    @TempDir
    Path dir;

    @BeforeAll
    static void connect() {
        client = RedisClient.create(redisUrl());
        redis = client.connect();
    }

    @AfterAll
    static void disconnect() {
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

    private Path savedCopy() {
        return dir.resolve("saved-rules.json");
    }

    private static void publish(StatefulRedisConnection<String, String> where, String snapshot) {
        where.sync().hset(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT, snapshot);
    }

    private ConfigurableApplicationContext startGateway(String url) {
        ConfigurableApplicationContext gateway = new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.port=0",
                "--gateway.redis.url=" + url,
                "--gateway.redis.command-timeout=200ms", // generous, so a busy test machine does not fail open
                "--gateway.ratelimit.snapshot-file=" + savedCopy());
        gateways.add(gateway);
        return gateway;
    }

    private static ResolvedRule imageRuleFor(ConfigurableApplicationContext gateway, String user) {
        return gateway.getBean(LiveRuleResolver.class)
                .resolve(RuleContext.postAuth("free", user, null, "GET", "/api/images/a"));
    }

    private static String imageRuleName(ConfigurableApplicationContext gateway) {
        return imageRuleFor(gateway, "alice").rule().name();
    }

    private static LiveRuleResolver.Source sourceOf(ConfigurableApplicationContext gateway) {
        return gateway.getBean(LiveRuleResolver.class).source();
    }

    private static Decision charge(ConfigurableApplicationContext gateway, String user) {
        ResolvedRule resolved = imageRuleFor(gateway, user);
        return gateway.getBean(RateLimiter.class).acquire(resolved.rule(), resolved.key(), 1);
    }

    // ---- where the rules come from at startup ----

    @Test
    void publishedRulesReplaceTheConfiguredOnesAndAreSaved() throws IOException {
        String published = snapshot(4, images(10, "published-images", 3));
        publish(redis, published);

        var gateway = startGateway(redisUrl());

        assertThat(imageRuleName(gateway)).isEqualTo("published-images");
        assertThat(sourceOf(gateway)).isEqualTo(LiveRuleResolver.Source.REDIS);
        assertThat(Files.readString(savedCopy())).isEqualTo(published);
    }

    @Test
    void withRedisDownTheSavedCopyIsUsed() throws IOException {
        Files.writeString(savedCopy(), snapshot(4, images(10, "saved-images", 3)));

        var gateway = startGateway(NO_REDIS);

        assertThat(imageRuleName(gateway)).isEqualTo("saved-images");
        assertThat(sourceOf(gateway)).isEqualTo(LiveRuleResolver.Source.DISK);
    }

    @Test
    void withRedisDownAndNoSavedCopyTheConfiguredRulesAreUsed() {
        var gateway = startGateway(NO_REDIS);

        assertThat(imageRuleName(gateway)).isEqualTo("free-images"); // from application.yml
        assertThat(sourceOf(gateway)).isEqualTo(LiveRuleResolver.Source.CONFIGURATION);
    }

    @Test
    void whenNothingHasBeenPublishedTheConfiguredRulesAreUsed() {
        var gateway = startGateway(redisUrl());

        assertThat(imageRuleName(gateway)).isEqualTo("free-images");
        assertThat(Files.exists(savedCopy())).isFalse();
    }

    @Test
    void aDamagedPublishedSnapshotFallsBackToTheSavedCopyAndDoesNotOverwriteIt() throws IOException {
        String good = snapshot(4, images(10, "saved-images", 3));
        Files.writeString(savedCopy(), good);
        publish(redis, "garbage");

        var gateway = startGateway(redisUrl());

        assertThat(imageRuleName(gateway)).isEqualTo("saved-images");
        assertThat(Files.readString(savedCopy())).isEqualTo(good);
    }

    @Test
    void aDamagedSavedCopyDoesNotStopTheGatewayFromStarting() throws IOException {
        Files.writeString(savedCopy(), "half a fi");

        var gateway = startGateway(NO_REDIS);

        assertThat(imageRuleName(gateway)).isEqualTo("free-images");
    }

    @Test
    void publishedRulesTheGatewayCannotEnforceAreNotUsed() {
        publish(redis, snapshot(4, images(10, "published-images", 3), localSyncLeakyBucket(11)));

        var gateway = startGateway(redisUrl());

        assertThat(imageRuleName(gateway)).isEqualTo("free-images");
        assertThat(Files.exists(savedCopy())).isFalse();
    }

    // ---- taking new rules while running ----

    @Test
    void aRefreshSwapsInNewRulesAndRefusesBadOnes() throws IOException {
        publish(redis, snapshot(2, images(10, "first", 3)));
        var gateway = startGateway(redisUrl());
        RuleSnapshotLoader loader = gateway.getBean(RuleSnapshotLoader.class);

        publish(redis, snapshot(3, images(10, "second", 3)));
        assertThat(loader.refreshFromRedis()).isEqualTo(Refresh.APPLIED);
        assertThat(imageRuleName(gateway)).isEqualTo("second");
        assertThat(loader.refreshFromRedis()).isEqualTo(Refresh.UNCHANGED);

        publish(redis, "garbage");
        assertThat(loader.refreshFromRedis()).isEqualTo(Refresh.REJECTED);
        assertThat(imageRuleName(gateway)).isEqualTo("second");
        assertThat(Files.readString(savedCopy())).contains("second");

        redis.sync().flushall();
        assertThat(loader.refreshFromRedis()).isEqualTo(Refresh.NOT_PUBLISHED);
        assertThat(imageRuleName(gateway)).isEqualTo("second"); // an empty Redis does not remove rules
    }

    @Test
    void changingALimitDoesNotResetWhatAUserHasAlreadyUsed() {
        publish(redis, snapshot(2, images(10, "images", 10)));
        var gateway = startGateway(redisUrl());
        String user = "user-" + UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            assertThat(charge(gateway, user).allowed()).isTrue(); // 2 tokens left
        }

        publish(redis, snapshot(3, images(10, "images", 20))); // same rule, twice the capacity
        assertThat(gateway.getBean(RuleSnapshotLoader.class).refreshFromRedis()).isEqualTo(Refresh.APPLIED);

        Decision next = charge(gateway, user);
        assertThat(next.limit()).isEqualTo(20);
        assertThat(next.remaining()).isEqualTo(1); // 2 left, 1 spent: not a fresh bucket of 20
    }

    // ---- Redis arriving and going away ----

    @Test
    void aGatewayStartedWithoutRedisStartsEnforcingAndLoadingOnceItAppears() {
        try (LateRedis late = new LateRedis()) {
            var gateway = startGateway(late.url());

            long start = System.nanoTime();
            Decision whileDown = charge(gateway, "alice");
            assertThat(whileDown.allowed()).isTrue();
            assertThat(whileDown.reason()).isEqualTo(Reason.LIMITER_UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));

            late.start();
            RedisClient lateClient = RedisClient.create(late.url());
            try (var connection = lateClient.connect()) {
                publish(connection, snapshot(2, images(10, "published-later", 3)));
            } finally {
                lateClient.shutdown();
            }

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> gateway.getBean(RedisConnector.class).ifConnected().isPresent());
            assertThat(gateway.getBean(RuleSnapshotLoader.class).refreshFromRedis()).isEqualTo(Refresh.APPLIED);

            assertThat(charge(gateway, "bob").reason()).isEqualTo(Reason.ALLOWED);
            charge(gateway, "bob");
            charge(gateway, "bob");
            assertThat(charge(gateway, "bob").reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        }
    }

    /**
     * The client library's default would wait longer after each failed reconnection, so that after an
     * outage of this length the next attempt could be many seconds away.
     */
    @Test
    void afterARedisOutageEnforcementResumesWithinSeconds() throws Exception {
        try (LateRedis late = new LateRedis()) {
            late.start();
            var gateway = startGateway(late.url());
            await().atMost(Duration.ofSeconds(5)).until(() -> charge(gateway, "warm-up").reason() == Reason.ALLOWED);

            late.stopServer();
            assertThat(charge(gateway, "alice").reason()).isEqualTo(Reason.LIMITER_UNAVAILABLE);
            Thread.sleep(12_000); // long enough for the default back-off to have grown well past the limit below

            late.startServerAgain();

            await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(100))
                    .until(() -> charge(gateway, "carol-" + UUID.randomUUID()).reason() == Reason.ALLOWED);
        }
    }
}
