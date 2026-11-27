package com.ratelimit.gateway.ratelimit.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NodeRegistryTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final Duration OFTEN = Duration.ofMillis(50);
    /** Long enough that no node can drop out by silence during a test that uses it. */
    private static final Duration LONG = Duration.ofSeconds(30);
    private static final Duration SOON = Duration.ofSeconds(3);

    static RedisClient client;
    static StatefulRedisConnection<String, String> redis;

    private final List<NodeRegistry> nodes = new ArrayList<>();

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
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
    void closeNodes() {
        nodes.forEach(NodeRegistry::close);
    }

    private NodeRegistry node(String id, Duration interval, Duration timeout) {
        return node(() -> redis, id, interval, timeout);
    }

    private NodeRegistry node(Supplier<StatefulRedisConnection<String, String>> connection, String id,
            Duration interval, Duration timeout) {
        var node = new NodeRegistry(connection, id, interval, timeout);
        nodes.add(node);
        return node;
    }

    private static long redisNowMs() {
        List<String> time = redis.sync().time();
        return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
    }

    // ---- counting ----

    @Test
    void aLoneNodeCountsOne() {
        var node = node("a", OFTEN, LONG);

        node.start();

        assertThat(node.nodeCount()).isEqualTo(1);
        assertThat(redis.sync().zrange(NodeRegistry.KEY, 0, -1)).containsExactly("a");
    }

    @Test
    void theCountIsRightAsSoonAsStartReturns() {
        node("a", LONG.dividedBy(2), LONG).start();
        var second = node("b", LONG.dividedBy(2), LONG);

        second.start(); // its next report is 15 s away

        assertThat(second.nodeCount()).isEqualTo(2);
    }

    @Test
    void everyNodeComesToCountAllOfThem() {
        var a = node("a", OFTEN, LONG);
        var b = node("b", OFTEN, LONG);
        var c = node("c", OFTEN, LONG);

        a.start();
        b.start();
        c.start();

        await().atMost(SOON).until(() -> a.nodeCount() == 3 && b.nodeCount() == 3 && c.nodeCount() == 3);
    }

    @Test
    void reportsAreStampedWithRedisTime() {
        long before = redisNowMs();

        node("a", OFTEN, LONG).start();

        assertThat(redis.sync().zscore(NodeRegistry.KEY, "a").longValue()).isBetween(before, redisNowMs());
    }

    // ---- leaving ----

    @Test
    void aNodeThatShutsDownIsForgottenAtOnce() {
        var a = node("a", OFTEN, LONG);
        var b = node("b", OFTEN, LONG);
        a.start();
        b.start();
        await().atMost(SOON).until(() -> a.nodeCount() == 2);

        b.close();

        // the timeout is 30 s, so only its goodbye can explain this
        await().atMost(SOON).until(() -> a.nodeCount() == 1);
        assertThat(redis.sync().zrange(NodeRegistry.KEY, 0, -1)).containsExactly("a");
    }

    @Test
    void aNodeThatGoesSilentCountsUntilTheTimeout() throws InterruptedException {
        Duration timeout = Duration.ofSeconds(1);
        var a = node("a", OFTEN, timeout);
        a.start();
        long reported = System.nanoTime();
        node("dies", OFTEN, timeout).beat(); // reports once and is never heard from again

        await().atMost(SOON).until(() -> a.nodeCount() == 2);
        Thread.sleep(300);
        assertThat(a.nodeCount()).as("still within the timeout").isEqualTo(2);

        await().atMost(SOON).until(() -> a.nodeCount() == 1);
        assertThat(Duration.ofNanos(System.nanoTime() - reported)).isGreaterThanOrEqualTo(timeout.minusMillis(50));
        assertThat(redis.sync().zrange(NodeRegistry.KEY, 0, -1)).containsExactly("a");
    }

    @Test
    void theSetExpiresOnceNobodyReports() {
        Duration timeout = Duration.ofMillis(500);

        node("a", OFTEN, timeout).beat();

        assertThat(redis.sync().pttl(NodeRegistry.KEY)).isBetween(1L, 500L);
        await().atMost(SOON).until(() -> redis.sync().exists(NodeRegistry.KEY) == 0);
    }

    @Test
    void closingTwiceOrWithoutStartingIsHarmless() {
        var never = node("a", OFTEN, LONG);
        var started = node("b", OFTEN, LONG);
        started.start();

        never.close();
        started.close();
        started.close();
        never.start(); // after close: does nothing

        assertThat(redis.sync().exists(NodeRegistry.KEY)).isZero();
    }

    // ---- when Redis fails ----

    @Test
    void withoutRedisTheNodeStartsAndCountsItself() {
        var node = node(() -> {
            throw new RedisConnectionException("down");
        }, "a", OFTEN, LONG);

        node.start();

        assertThat(node.nodeCount()).isEqualTo(1);
    }

    @Test
    void failedReportsKeepTheLastCountAndReportingResumesAfterwards() {
        var down = new AtomicBoolean();
        var failures = new AtomicInteger();
        var a = node(() -> {
            if (down.get()) {
                failures.incrementAndGet();
                throw new RedisConnectionException("down");
            }
            return redis;
        }, "a", OFTEN, Duration.ofMillis(300));
        var b = node("b", OFTEN, Duration.ofMillis(300));
        a.start();
        b.start();
        await().atMost(SOON).until(() -> a.nodeCount() == 2);

        down.set(true);
        b.close();
        await().atMost(SOON).until(() -> failures.get() >= 12); // well past the node timeout

        assertThat(a.nodeCount()).as("nothing better to go on").isEqualTo(2);

        down.set(false);
        // proves the failures did not stop the timer
        await().atMost(SOON).until(() -> a.nodeCount() == 1);
    }

    // ---- settings ----

    @Test
    void aTimeoutShorterThanTwoReportsIsRefused() {
        assertThatThrownBy(() -> node("a", Duration.ofSeconds(1), Duration.ofMillis(1_999)))
                .hasMessageContaining("twice the heartbeat interval");
        assertThatThrownBy(() -> node("a", Duration.ZERO, LONG)).hasMessageContaining("at least 1 ms");
    }
}
