package com.ratelimit.gateway.ratelimit.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.rules.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterConcurrencyTests {

    private static final int THREADS = 64;
    private static final int ATTEMPTS_PER_THREAD = 100;

    /** Runs {@code task} on many virtual threads released at the same moment. */
    private static void runConcurrently(int threads, IntFunction<Runnable> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Runnable work = task.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    work.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(); // surfaces any exception from a worker
            }
        }
    }

    @Test
    void exactlyCapacityRequestsPassWhenManyThreadsHitOneKey() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new FakeNanoClock(), 1_000); // frozen time: no refill
        Rule rule = TestRules.tokenBucket(1, 1_000, 1);
        AtomicInteger admitted = new AtomicInteger();

        runConcurrently(THREADS, i -> () -> {
            for (int n = 0; n < ATTEMPTS_PER_THREAD; n++) {
                if (limiter.acquire(rule, "alice", 1).allowed()) {
                    admitted.incrementAndGet();
                }
            }
        });

        assertThat(THREADS * ATTEMPTS_PER_THREAD).isGreaterThan(1_000); // the limit really was contended
        assertThat(admitted.get()).isEqualTo(1_000);
    }

    @Test
    void eachKeyGetsExactlyItsOwnCapacityUnderContention() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new FakeNanoClock(), 1_000);
        Rule rule = TestRules.tokenBucket(1, 50, 1);
        int keys = 8;
        AtomicInteger[] admitted = new AtomicInteger[keys];
        for (int k = 0; k < keys; k++) {
            admitted[k] = new AtomicInteger();
        }

        runConcurrently(keys * 16, i -> {
            int key = i % keys;
            return () -> {
                for (int n = 0; n < 100; n++) {
                    if (limiter.acquire(rule, "key-" + key, 1).allowed()) {
                        admitted[key].incrementAndGet();
                    }
                }
            };
        });

        for (int k = 0; k < keys; k++) {
            assertThat(admitted[k].get()).as("key-%d", k).isEqualTo(50);
        }
    }

    @Test
    void withRealTimeAdmissionsStayWithinCapacityPlusRefill() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(1_000);
        long capacity = 100;
        double refillPerSec = 1_000;
        Rule rule = TestRules.tokenBucket(1, capacity, refillPerSec);
        AtomicInteger admitted = new AtomicInteger();
        long deadline = System.nanoTime() + 300_000_000L;

        long startedAt = System.nanoTime();
        runConcurrently(32, i -> () -> {
            while (System.nanoTime() < deadline) {
                if (limiter.acquire(rule, "alice", 1).allowed()) {
                    admitted.incrementAndGet();
                }
            }
        });
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1e9;

        double upperBound = capacity + refillPerSec * elapsedSeconds;
        assertThat((double) admitted.get()).isLessThanOrEqualTo(upperBound);
        assertThat(admitted.get()).isGreaterThanOrEqualTo((int) capacity); // not starved
    }
}
