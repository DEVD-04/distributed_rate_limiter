package com.ratelimit.gateway.ratelimit.strict;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.rules.Rule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * How long one limiter call takes against a real Redis. A measurement, not a test of behaviour, so it
 * only runs when {@code RUN_BENCHMARKS=1} and asserts nothing about speed (a laptop with Redis in a
 * Docker VM makes any fixed threshold meaningless). Results go to {@code build/benchmarks/}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARKS", matches = "1")
class LimiterLatencyBenchmark {

    private static final int KEYS = 1_000;
    private static final int WARMUP = 5_000;
    private static final int SEQUENTIAL_CALLS = 20_000;
    private static final int THREADS = 32;
    private static final int CALLS_PER_THREAD = 1_000;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;
    static StrictRedisLimiter limiter;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
        limiter = new StrictRedisLimiter(connection);
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    private record Stats(String label, int calls, double p50, double p95, double p99, double max, double perSecond) {
        String row() {
            return String.format("%-34s %7d   p50 %6.2f   p95 %6.2f   p99 %6.2f   max %7.2f   %8.0f calls/s",
                    label, calls, p50, p95, p99, max, perSecond);
        }
    }

    private static Stats stats(String label, long[] nanos, double elapsedSeconds) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        java.util.function.DoubleUnaryOperator ms = q -> sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))] / 1e6;
        return new Stats(label, sorted.length, ms.applyAsDouble(0.50), ms.applyAsDouble(0.95), ms.applyAsDouble(0.99),
                sorted[sorted.length - 1] / 1e6, sorted.length / elapsedSeconds);
    }

    private static Stats sequential(String label, RateLimiter target, Rule rule) {
        for (int i = 0; i < WARMUP; i++) {
            target.acquire(rule, "k" + (i % KEYS), 1);
        }
        long[] nanos = new long[SEQUENTIAL_CALLS];
        long begin = System.nanoTime();
        for (int i = 0; i < SEQUENTIAL_CALLS; i++) {
            long t = System.nanoTime();
            target.acquire(rule, "k" + (i % KEYS), 1);
            nanos[i] = System.nanoTime() - t;
        }
        return stats(label + " / 1 thread", nanos, (System.nanoTime() - begin) / 1e9);
    }

    private static Stats concurrent(String label, RateLimiter target, Rule rule) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            target.acquire(rule, "k" + (i % KEYS), 1);
        }
        long[][] perThread = new long[THREADS][CALLS_PER_THREAD];
        CountDownLatch go = new CountDownLatch(1);
        long begin;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int id = t;
                futures.add(executor.submit(() -> {
                    go.await();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        long start = System.nanoTime();
                        target.acquire(rule, "k" + ((id * 31 + i) % KEYS), 1);
                        perThread[id][i] = System.nanoTime() - start;
                    }
                    return null;
                }));
            }
            begin = System.nanoTime();
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        double elapsed = (System.nanoTime() - begin) / 1e9;
        return stats(label + " / " + THREADS + " threads", Arrays.stream(perThread).flatMapToLong(Arrays::stream).toArray(), elapsed);
    }

    /**
     * Offers calls at a fixed rate instead of as fast as possible. Each call is timed from the moment it
     * was due, not from when a thread got round to sending it, so any queueing shows up in the result
     * (a plain timer would hide it).
     */
    private static Stats atFixedRate(String label, RateLimiter target, Rule rule, int callsPerSecond, int seconds) throws Exception {
        int total = callsPerSecond * seconds;
        long[] nanos = new long[total];
        long intervalNanos = 1_000_000_000L / callsPerSecond;
        for (int i = 0; i < WARMUP; i++) {
            target.acquire(rule, "k" + (i % KEYS), 1);
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(total);
            long begin = System.nanoTime();
            for (int i = 0; i < total; i++) {
                long due = begin + i * intervalNanos;
                // Spin rather than sleep: on Windows a sleeping thread wakes on a ~15 ms timer tick, and
                // because latency is measured from the due time that lateness would be blamed on Redis.
                while (System.nanoTime() < due) {
                    Thread.onSpinWait();
                }
                int n = i;
                futures.add(executor.submit(() -> {
                    target.acquire(rule, "k" + (n % KEYS), 1);
                    nanos[n] = System.nanoTime() - due;
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            return stats(label, nanos, (System.nanoTime() - begin) / 1e9);
        }
    }

    @Test
    void measureAtTheDesignLoads() throws Exception {
        Rule rule = TestRules.tokenBucket(1, 1_000_000, 1_000_000);
        List<String> report = new ArrayList<>();
        report.add("Open loop (latency measured from when each call was due); token bucket; 5 s per rate");
        // The design's loads are requests per second; every request makes two limiter calls (IP stage + user stage).
        report.add(atFixedRate("2,000 req/s steady = 4,000 calls/s", limiter, rule, 4_000, 5).row());
        report.add(atFixedRate("5,000 req/s burst  = 10,000 calls/s", limiter, rule, 10_000, 5).row());

        Path out = Path.of("build", "benchmarks", "limiter-latency-open-loop.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
        report.forEach(System.out::println);
        assertThat(report).hasSize(3);
    }

    @Test
    void measureLimiterLatencyForEachAlgorithm() throws Exception {
        Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put("token bucket", TestRules.tokenBucket(1, 1_000_000, 1_000_000));
        rules.put("sliding window", TestRules.slidingWindow(2, 100_000, 1_000));
        rules.put("leaky bucket", TestRules.leakyBucket(3, 100_000, 100_000)); // 0.01 ms apart: practically never waits

        List<String> report = new ArrayList<>();
        report.add("Redis 7 in Docker Desktop on the same laptop; latency in ms per limiter call; " + KEYS + " rotating keys");
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            report.add(sequential(entry.getKey(), limiter, entry.getValue()).row());
            report.add(concurrent(entry.getKey(), limiter, entry.getValue()).row());
        }

        Path out = Path.of("build", "benchmarks", "limiter-latency.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
        assertThat(report).hasSize(7);
        report.forEach(System.out::println);
    }
}
