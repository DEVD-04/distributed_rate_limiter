package com.ratelimit.gateway.ratelimit.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.local.InMemoryRateLimiter;
import com.ratelimit.rules.Rule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Several limiters, standing for several gateway nodes, borrowing from one real Redis. */
@Testcontainers
class LocalSyncRedisTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    private List<LocalSyncLimiter> nodes(int count, long batch) {
        var redis = new TokenLeaseClient(() -> connection);
        LeaseSource counted = (ruleId, subject, params, want, need, returned, n) -> {
            calls.incrementAndGet();
            return redis.lease(ruleId, subject, params, want, need, returned, n);
        };
        List<LocalSyncLimiter> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var fallback = new InMemoryRateLimiter(1_000);
            nodes.add(new LocalSyncLimiter(counted, () -> count, (rule, key, cost) -> {
                failures.incrementAndGet();
                return fallback.acquire(rule, key, cost);
            }, batch, Duration.ofSeconds(1), 1_000, System::nanoTime));
        }
        return nodes;
    }

    private static long burst(List<LocalSyncLimiter> nodes, Rule rule, String key, int requestsPerNode)
            throws InterruptedException, ExecutionException {
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (LocalSyncLimiter node : nodes) {
                for (int i = 0; i < requestsPerNode; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return node.acquire(rule, key, 1).allowed();
                    }));
                }
            }
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }
            return allowed;
        }
    }

    @Test
    void threeNodesUnderABurstAdmitExactlyTheCapacityInTheEnd() throws Exception {
        List<LocalSyncLimiter> nodes = nodes(3, 5);
        Rule rule = TestRules.tokenBucket(1, 300, 0.000001);
        String who = "s-" + UUID.randomUUID();

        long allowed = burst(nodes, rule, who, 400);
        assertThat(allowed).isLessThanOrEqualTo(300);

        // whatever a node still holds comes out when it is asked again: nothing was lost and nothing made up
        for (int round = 0; round < 300; round++) {
            for (LocalSyncLimiter node : nodes) {
                if (node.acquire(rule, who, 1).allowed()) {
                    allowed++;
                }
            }
        }
        assertThat(failures).as("every lease call got an answer").hasValue(0);
        assertThat(allowed).isEqualTo(300);
    }

    @Test
    void aLoneBusyNodeCanUseTheWholeBucketAlthoughItIsOneOfThree() {
        LocalSyncLimiter busy = nodes(3, 10).get(0);
        Rule rule = TestRules.tokenBucket(2, 300, 0.000001);
        String who = "s-" + UUID.randomUUID();

        long allowed = 0;
        for (int i = 0; i < 400; i++) {
            if (busy.acquire(rule, who, 1).allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(300);
    }

    @Test
    void withARefillTheClusterStaysWithinCapacityPlusRefill() throws InterruptedException {
        List<LocalSyncLimiter> nodes = nodes(3, 5);
        Rule rule = TestRules.tokenBucket(3, 50, 100);
        String who = "s-" + UUID.randomUUID();
        long started = System.nanoTime();

        long allowed = 0;
        while (System.nanoTime() - started < Duration.ofMillis(1_500).toNanos()) {
            for (LocalSyncLimiter node : nodes) {
                if (node.acquire(rule, who, 1).allowed()) {
                    allowed++;
                }
            }
            Thread.sleep(1);
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        assertThat((double) allowed).isLessThanOrEqualTo(50 + 100 * seconds + 1);
        assertThat((double) allowed).as("and gets most of it").isGreaterThanOrEqualTo(0.8 * (50 + 100 * seconds));
    }

    @Test
    void borrowingInBatchesNeedsFarFewerCallsThanRequests() {
        LocalSyncLimiter node = nodes(1, 100).get(0);
        Rule rule = TestRules.tokenBucket(4, 10_000, 1);
        String who = "s-" + UUID.randomUUID();

        for (int i = 0; i < 2_000; i++) {
            assertThat(node.acquire(rule, who, 1).allowed()).isTrue();
        }

        assertThat(calls).hasValue(20);
    }
}
