package com.ratelimit.gateway.rules;

import static com.ratelimit.gateway.rules.TestSnapshots.images;
import static com.ratelimit.gateway.rules.TestSnapshots.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.GatewayApplication;
import com.ratelimit.gateway.redis.LateRedis;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A running gateway following changes to the published rules, with a real Redis. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class GatewayLiveUpdateTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Used when a test must prove that announcements alone deliver a change. */
    private static final String NO_POLLING = "1h";

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
    void startFromVersionOne() {
        redis.sync().flushall();
        store(redis, 1, snapshot(1, images(10, "v1", 3)));
    }

    @AfterEach
    void stopGateways() {
        gateways.forEach(ConfigurableApplicationContext::close);
    }

    private static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    /** Writes a snapshot without announcing it: what a gateway sees when the message is lost. */
    private static void store(StatefulRedisConnection<String, String> where, long version, String snapshot) {
        where.sync().hset(SnapshotKeys.KEY, Map.of(
                SnapshotKeys.FIELD_VERSION, Long.toString(version), SnapshotKeys.FIELD_SNAPSHOT, snapshot));
    }

    /** Writes and announces, as the policy service does. */
    private static void publish(StatefulRedisConnection<String, String> where, long version, String snapshot) {
        store(where, version, snapshot);
        where.sync().publish(SnapshotKeys.CHANNEL, Long.toString(version));
    }

    private static void publishImages(long version) {
        publish(redis, version, snapshot(version, images(10, "v" + version, 3)));
    }

    private ConfigurableApplicationContext startGateway(String url, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--gateway.redis.url=" + url,
                "--gateway.redis.command-timeout=200ms",
                "--gateway.ratelimit.snapshot-file=" + dir.resolve("saved-" + gateways.size() + ".json")));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext gateway =
                new SpringApplicationBuilder(GatewayApplication.class).run(args.toArray(String[]::new));
        gateways.add(gateway);
        return gateway;
    }

    private static String ruleInForce(ConfigurableApplicationContext gateway) {
        return gateway.getBean(LiveRuleResolver.class)
                .resolve(RuleContext.postAuth("free", "alice", null, "GET", "/api/images/a")).rule().name();
    }

    private static void awaitRule(ConfigurableApplicationContext gateway, String name, Duration within) {
        await().atMost(within).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(ruleInForce(gateway)).isEqualTo(name));
    }

    /** Announcements only reach subscribers, so a test must not publish before the gateway is one. */
    private static void awaitSubscribers(StatefulRedisConnection<String, String> where, long count) {
        await().atMost(Duration.ofSeconds(5)).until(
                () -> where.sync().pubsubNumsub(SnapshotKeys.CHANNEL).get(SnapshotKeys.CHANNEL) == count);
    }

    // ---- announcements ----

    @Test
    void anAnnouncedChangeAppliesWithinTwoSecondsOnEveryGateway() {
        var gateways3 = List.of(
                startGateway(redisUrl(), "--gateway.ratelimit.update-poll-interval=" + NO_POLLING),
                startGateway(redisUrl(), "--gateway.ratelimit.update-poll-interval=" + NO_POLLING),
                startGateway(redisUrl(), "--gateway.ratelimit.update-poll-interval=" + NO_POLLING));
        awaitSubscribers(redis, 3);

        publishImages(2);

        long start = System.nanoTime();
        for (var gateway : gateways3) {
            awaitRule(gateway, "v2", Duration.ofSeconds(2));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aRapidSeriesOfChangesEndsOnTheLastOne() {
        var gateway = startGateway(redisUrl(), "--gateway.ratelimit.update-poll-interval=" + NO_POLLING);
        awaitSubscribers(redis, 1);

        for (long version = 2; version <= 41; version++) {
            publishImages(version);
        }

        awaitRule(gateway, "v41", Duration.ofSeconds(2));
    }

    // ---- polling, for when announcements are lost ----

    /** The design's pass criterion with pub/sub disabled, at the default 10 s interval. */
    @Test
    void withAnnouncementsOffAChangeAppliesWithinFifteenSecondsByDefault() {
        var gateway = startGateway(redisUrl(), "--gateway.ratelimit.subscribe-to-updates=false");
        assertThat(redis.sync().pubsubNumsub(SnapshotKeys.CHANNEL).get(SnapshotKeys.CHANNEL)).isZero();

        publishImages(2); // announced, but nobody is listening

        awaitRule(gateway, "v2", Duration.ofSeconds(15));
    }

    @Test
    void aRepairThatKeepsTheSameVersionIsStillPickedUp() {
        var gateway = startGateway(redisUrl(), "--gateway.ratelimit.subscribe-to-updates=false",
                "--gateway.ratelimit.update-poll-interval=300ms");

        store(redis, 1, snapshot(1, images(10, "same-version-new-content", 3)));

        awaitRule(gateway, "same-version-new-content", Duration.ofSeconds(3));
    }

    @Test
    void aBadSnapshotIsRefusedOnceAndTheNextGoodOneApplies(CapturedOutput output) throws Exception {
        var gateway = startGateway(redisUrl(), "--gateway.ratelimit.update-poll-interval=100ms");

        publish(redis, 2, "garbage");
        Thread.sleep(1_200); // read a dozen times

        assertThat(ruleInForce(gateway)).isEqualTo("v1");
        assertThat(output.getAll().split("Rejected an invalid rules snapshot", -1).length - 1).isEqualTo(1);

        publishImages(3);
        awaitRule(gateway, "v3", Duration.ofSeconds(2));
    }

    @Test
    void anEmptiedRedisLeavesTheRulesInForce() throws Exception {
        var gateway = startGateway(redisUrl(), "--gateway.ratelimit.update-poll-interval=100ms");

        redis.sync().flushall();
        Thread.sleep(800);

        assertThat(ruleInForce(gateway)).isEqualTo("v1");
        publishImages(2);
        awaitRule(gateway, "v2", Duration.ofSeconds(2)); // and polling carried on through it
    }

    // ---- outages ----

    @Test
    void announcementsAreHeardAgainAfterRedisRestarts() throws Exception {
        try (LateRedis restartable = new LateRedis()) {
            restartable.start();
            RedisClient restartClient = RedisClient.create(restartable.url());
            try (var connection = restartClient.connect()) {
                store(connection, 1, snapshot(1, images(10, "before", 3)));
            }
            var gateway = startGateway(restartable.url(), "--gateway.ratelimit.update-poll-interval=" + NO_POLLING);
            awaitRule(gateway, "before", Duration.ofSeconds(2));

            restartable.stopServer();
            Thread.sleep(3_000);
            restartable.startServerAgain();

            try (var connection = restartClient.connect()) {
                awaitSubscribers(connection, 1); // resubscribed by itself
                publish(connection, 2, snapshot(2, images(10, "after-restart", 3)));

                awaitRule(gateway, "after-restart", Duration.ofSeconds(2));
            } finally {
                restartClient.shutdown();
            }
        }
    }

    /** What was announced while a gateway could not hear it is picked up as soon as it subscribes. */
    @Test
    void aGatewayStartedWithoutRedisCatchesUpWhenItAppearsWithoutWaitingForAPoll() {
        try (LateRedis late = new LateRedis()) {
            var gateway = startGateway(late.url(), "--gateway.ratelimit.update-poll-interval=" + NO_POLLING);
            assertThat(ruleInForce(gateway)).isEqualTo("free-images"); // from application.yml

            late.start();
            RedisClient lateClient = RedisClient.create(late.url());
            try (var connection = lateClient.connect()) {
                store(connection, 5, snapshot(5, images(10, "published-before-it-could-listen", 3)));
            } finally {
                lateClient.shutdown();
            }

            awaitRule(gateway, "published-before-it-could-listen", Duration.ofSeconds(6));
        }
    }
}
