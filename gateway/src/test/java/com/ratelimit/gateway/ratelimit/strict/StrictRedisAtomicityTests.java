package com.ratelimit.gateway.ratelimit.strict;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.rules.Rule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The point of STRICT mode: several gateway nodes sharing one Redis never admit more than the limit.
 * Three limiter instances, each with its own Redis connection, stand in for three nodes; 200 threads
 * are spread across them and all start at the same instant.
 */
@Testcontainers
class StrictRedisAtomicityTests {

    private static final int NODES = 3;
    private static final int THREADS = 200;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;
    static final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    static final List<StrictRedisLimiter> nodes = new ArrayList<>();

    @BeforeAll
    static void startNodes() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        for (int i = 0; i < NODES; i++) {
            StatefulRedisConnection<String, String> connection = client.connect();
            connections.add(connection);
            nodes.add(new StrictRedisLimiter(connection));
        }
    }

    @AfterAll
    static void stopNodes() {
        connections.forEach(StatefulRedisConnection::close);
        client.shutdown();
    }

    /**
     * Runs {@code THREADS} virtual threads released together; thread {@code t} uses node {@code t % 3}
     * and the subject {@code subjectOf(t)}, and tries {@code attempts} times.
     *
     * @return admitted requests per subject
     */
    private static Map<String, Integer> hammer(Rule rule, int attempts, IntFunction<String> subjectOf) throws Exception {
        Map<String, AtomicInteger> admitted = new ConcurrentHashMap<>();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                StrictRedisLimiter node = nodes.get(t % NODES);
                String subject = subjectOf.apply(t);
                admitted.computeIfAbsent(subject, s -> new AtomicInteger());
                futures.add(executor.submit(() -> {
                    go.await();
                    for (int n = 0; n < attempts; n++) {
                        if (node.acquire(rule, subject, 1).allowed()) {
                            admitted.get(subject).incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        Map<String, Integer> result = new ConcurrentHashMap<>();
        admitted.forEach((subject, count) -> result.put(subject, count.get()));
        return result;
    }

    private static String subject() {
        return "s-" + UUID.randomUUID();
    }

    @Test
    void tokenBucketAdmitsExactlyCapacityAcrossThreeNodesAndTwoHundredThreads() throws Exception {
        Rule rule = TestRules.tokenBucket(1, 500, 0.000001); // nothing refills during the test
        String who = subject();

        Map<String, Integer> admitted = hammer(rule, 10, t -> who); // 2,000 attempts for 500 tokens

        assertThat(admitted.get(who)).isEqualTo(500);
    }

    @Test
    void slidingWindowAdmitsExactlyTheLimitAcrossThreeNodesAndTwoHundredThreads() throws Exception {
        Rule rule = TestRules.slidingWindow(2, 300, 60_000);
        String who = subject();

        Map<String, Integer> admitted = hammer(rule, 5, t -> who); // 1,000 attempts for 300 slots

        assertThat(admitted.get(who)).isEqualTo(300);
    }

    @Test
    void everyKeyGetsExactlyItsOwnLimitWhenManyKeysAreHammeredAtOnce() throws Exception {
        Rule rule = TestRules.tokenBucket(3, 50, 0.000001);
        String prefix = subject() + "-";

        Map<String, Integer> admitted = hammer(rule, 20, t -> prefix + (t % 20)); // 10 threads x 20 attempts per key

        assertThat(admitted).hasSize(20);
        assertThat(admitted.values()).containsOnly(50);
    }

    @Test
    void aCostlyRuleStillNeverOverspends() throws Exception {
        Rule rule = TestRules.tokenBucket(4, 1000, 0.000001, 7); // 7 tokens per request: 142 requests fit
        String who = subject();

        Map<String, Integer> admitted = hammer(rule, 5, t -> who);

        assertThat(admitted.get(who)).isEqualTo(142);
    }

    @Test
    void withRefillAdmissionsStayWithinCapacityPlusWhatTimeAllows() throws Exception {
        Rule rule = TestRules.tokenBucket(5, 100, 200); // 200 tokens per second
        String who = subject();
        AtomicInteger admitted = new AtomicInteger();
        long deadline = System.nanoTime() + 400_000_000L;
        CountDownLatch go = new CountDownLatch(1);

        long startedAt = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                StrictRedisLimiter node = nodes.get(t % NODES);
                futures.add(executor.submit(() -> {
                    go.await();
                    while (System.nanoTime() < deadline) {
                        if (node.acquire(rule, who, 1).allowed()) {
                            admitted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1e9;

        // Redis time can run a millisecond or two ahead of this measurement, hence the small allowance.
        double upperBound = 100 + 200 * elapsedSeconds + 2;
        assertThat((double) admitted.get()).isLessThanOrEqualTo(upperBound);
        assertThat(admitted.get()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void leakyBucketReleasesRequestsFromAllNodesAtOneGlobalRate() throws Exception {
        Rule rule = TestRules.leakyBucket(6, 100, 200); // one release every 10 ms, 200 may queue
        String who = subject();
        List<Long> releasedAtNanos = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < 60; t++) { // 20 requests per node
                StrictRedisLimiter node = nodes.get(t % NODES);
                futures.add(executor.submit(() -> {
                    go.await();
                    Decision d = node.acquire(rule, who, 1);
                    assertThat(d.allowed()).isTrue();
                    return new long[] {System.nanoTime(), d.waitedMs()}; // the moment the request is let go
                }));
            }
            go.countDown();
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                releasedAtNanos.add(r[0]);
                waits.add(r[1]);
            }
        }
        releasedAtNanos.sort(Long::compare);
        long spanMs = (releasedAtNanos.get(releasedAtNanos.size() - 1) - releasedAtNanos.get(0)) / 1_000_000;

        // One shared schedule releases 60 requests 10 ms apart, so the last goes about 590 ms after the
        // first. Three independent schedules (one per node) would release them in about 200 ms.
        assertThat(spanMs).isBetween(540L, 720L);
        // Each wait is measured from that request's own arrival, so the longest is just under 590 ms.
        assertThat(waits.stream().max(Long::compare).orElseThrow()).isBetween(540L, 640L);
    }
}
