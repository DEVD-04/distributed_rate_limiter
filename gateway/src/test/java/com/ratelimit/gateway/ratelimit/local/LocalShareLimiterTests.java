package com.ratelimit.gateway.ratelimit.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.rules.Rule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Each node's share of a limit. The clock moves only when a test moves it, and waits are recorded, not slept. */
class LocalShareLimiterTests {

    private final FakeNanoClock clock = new FakeNanoClock();
    private final AtomicInteger nodes = new AtomicInteger(3);
    /** The waits asked for, in milliseconds. */
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();

    private LocalShareLimiter node() {
        return node(100);
    }

    private LocalShareLimiter node(int maxWaiting) {
        return new LocalShareLimiter(nodes::get, 1_000, maxWaiting, clock,
                nanos -> sleeps.add(Math.round(nanos / 1e6)));
    }

    private static long allowed(LocalShareLimiter limiter, Rule rule, int requests) {
        long allowed = 0;
        for (int i = 0; i < requests; i++) {
            if (limiter.acquire(rule, "alice", 1).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    // ---- token bucket ----

    @Test
    void eachNodeGetsItsShareOfTheCapacitySoTogetherTheyStayWithinIt() {
        Rule rule = TestRules.tokenBucket(1, 100, 0.000001);

        long together = 0;
        for (int i = 0; i < 3; i++) {
            long one = allowed(node(), rule, 200);
            assertThat(one).isEqualTo(33);
            together += one;
        }

        assertThat(together).isEqualTo(99);
    }

    @Test
    void theRefillIsSharedToo() {
        var limiter = node();
        Rule rule = TestRules.tokenBucket(1, 100, 6); // 2 a second for each of 3
        allowed(limiter, rule, 200);

        clock.advanceMillis(5_000);

        assertThat(allowed(limiter, rule, 200)).isEqualTo(10);
    }

    @Test
    void aLoneNodeGetsTheWholeLimit() {
        nodes.set(1);

        assertThat(allowed(node(), TestRules.tokenBucket(1, 100, 0.000001), 200)).isEqualTo(100);
    }

    @Test
    void aNodeCountBelowOneCountsAsOne() {
        nodes.set(0);

        assertThat(allowed(node(), TestRules.tokenBucket(1, 10, 0.000001), 50)).isEqualTo(10);
    }

    @Test
    void whenTheLimitIsSmallerThanTheNodeCountEachStillAllowsOne() {
        // agreed: rounding down would block every request, so three nodes can allow 3 of a limit of 2
        assertThat(allowed(node(), TestRules.tokenBucket(1, 2, 0.000001), 10)).isEqualTo(1);
    }

    @Test
    void aShareIsNeverSmallerThanWhatOneRequestCosts() {
        Rule costly = TestRules.tokenBucket(1, 10, 0.000001, 5); // a third of 10 could not pay for one

        assertThat(allowed(node(), costly, 10)).isEqualTo(1);
    }

    @Test
    void theHeadersDescribeTheShare() {
        Decision d = node().acquire(TestRules.tokenBucket(1, 100, 1), "alice", 1);

        assertThat(d.limit()).isEqualTo(33);
        assertThat(d.remaining()).isEqualTo(32);
        assertThat(d.reason()).isEqualTo(Reason.ALLOWED);
    }

    @Test
    void aChangedNodeCountAppliesToWhatWasAlreadyUsed() {
        var limiter = node();
        nodes.set(1);
        Rule rule = TestRules.tokenBucket(1, 100, 0.000001);
        allowed(limiter, rule, 40); // 60 left

        nodes.set(4);
        assertThat(allowed(limiter, rule, 100)).as("clamped to the share of 25").isEqualTo(25);

        nodes.set(1);
        assertThat(allowed(limiter, rule, 100)).as("a bigger share does not refill by itself").isZero();
    }

    @Test
    void usedUpStaysUsedUpUntilTimeRefillsIt() {
        var limiter = node();
        Rule rule = TestRules.tokenBucket(1, 30, 0.3); // 10 each, one back every 10 s
        assertThat(allowed(limiter, rule, 50)).isEqualTo(10);

        clock.advance(Duration.ofSeconds(20)); // as if Redis had come back and failed again meanwhile

        assertThat(allowed(limiter, rule, 50)).isEqualTo(2);
    }

    // ---- sliding window ----

    @Test
    void aSlidingWindowAllowsItsShareInAnyWindow() {
        var limiter = node();
        Rule rule = TestRules.slidingWindow(1, 10, 1_000); // 3 each

        assertThat(allowed(limiter, rule, 10)).isEqualTo(3);
        Decision refused = limiter.acquire(rule, "alice", 1);

        assertThat(refused.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        assertThat(refused.limit()).isEqualTo(3);
        assertThat(refused.remaining()).isZero();
        assertThat(refused.retryAfterMs()).isEqualTo(1_000);
    }

    @Test
    void aRequestCountsForExactlyOneWindow() {
        var limiter = node();
        Rule rule = TestRules.slidingWindow(1, 3, 1_000); // 1 each
        limiter.acquire(rule, "alice", 1);

        clock.advance(Duration.ofSeconds(1).minusNanos(1));
        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isFalse();

        clock.advanceNanos(1);
        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
    }

    @Test
    void theWindowRollsAndRefusalsAreNotRecorded() {
        var limiter = node();
        Rule rule = TestRules.slidingWindow(1, 6, 1_000); // 2 each
        limiter.acquire(rule, "alice", 1);                 // at 0
        clock.advanceMillis(600);
        limiter.acquire(rule, "alice", 1);                 // at 600
        allowed(limiter, rule, 50);                        // all refused

        assertThat(limiter.acquire(rule, "alice", 1).retryAfterMs()).isEqualTo(400);
        clock.advanceMillis(400);
        assertThat(allowed(limiter, rule, 10)).as("only the one from time 0 has left").isEqualTo(1);
        clock.advanceMillis(600);
        assertThat(allowed(limiter, rule, 10)).isEqualTo(1);
    }

    @Test
    void whenTheShareShrinksTheRetryTimeAllowsForEverythingThatMustLeaveFirst() {
        var limiter = node();
        nodes.set(1);
        Rule rule = TestRules.slidingWindow(1, 4, 1_000);
        for (int i = 0; i < 4; i++) { // at 0, 100, 200 and 300
            limiter.acquire(rule, "alice", 1);
            clock.advanceMillis(100);
        }

        nodes.set(2); // 2 each now: three of the four must leave before there is room
        Decision refused = limiter.acquire(rule, "alice", 1);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMs()).isEqualTo(800); // the one from 200 leaves at 1200; it is now 400
        assertThat(refused.resetMs()).isEqualTo(900);
        clock.advanceMillis(799);
        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isFalse();
        clock.advanceMillis(1);
        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
    }

    @Test
    void aLongWindowIsNotForgottenEarlyEvenAfterTheRuleGrewLonger() {
        var limiter = node();
        limiter.acquire(TestRules.slidingWindow(1, 3, 1_000), "alice", 1);
        Rule hour = TestRules.slidingWindow(1, 3, 3_600_000);
        assertThat(limiter.acquire(hour, "alice", 1).allowed()).isFalse();

        clock.advance(Duration.ofMinutes(59)); // far longer than state with a 1 s window is kept

        assertThat(limiter.acquire(hour, "alice", 1).allowed()).isFalse();
    }

    @Test
    void concurrentRequestsNeverExceedTheShare() throws InterruptedException, ExecutionException {
        var limiter = new LocalShareLimiter(() -> 4, 1_000, 100);
        Rule rule = TestRules.slidingWindow(1, 200, 60_000);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return limiter.acquire(rule, "alice", 1).allowed();
                }));
            }
            start.countDown();
        }
        int allowed = 0;
        for (Future<Boolean> result : results) {
            allowed += result.get() ? 1 : 0;
        }

        assertThat(allowed).isEqualTo(50);
    }

    // ---- leaky bucket ----

    @Test
    void eachNodeReleasesAtItsShareOfTheDrainRate() {
        var limiter = node();
        Rule rule = TestRules.leakyBucket(1, 9, 18); // 3 a second each; at most 2 s of waiting

        assertThat(allowed(limiter, rule, 4)).isEqualTo(4);

        assertThat(sleeps).containsExactly(333L, 667L, 1_000L); // the first goes at once
    }

    @Test
    void noRequestEverWaitsLongerThanTheRuleAllows() {
        var limiter = node();
        Rule rule = TestRules.leakyBucket(1, 3, 6); // the rule's longest wait is 2 s; this node releases 1 a second

        assertThat(allowed(limiter, rule, 20)).isEqualTo(3);
        Decision refused = limiter.acquire(rule, "alice", 1);

        assertThat(sleeps).containsExactly(1_000L, 2_000L);
        assertThat(refused.reason()).isEqualTo(Reason.QUEUE_FULL);
        assertThat(refused.retryAfterMs()).isEqualTo(1_000);
        assertThat(refused.limit()).as("a share of the queue of 6").isEqualTo(2);
    }

    @Test
    void aQueueSmallerThanTheNodeCountStillLetsRequestsThrough() {
        var limiter = node();
        Rule rule = TestRules.leakyBucket(1, 10, 2); // longest wait 200 ms; a slot every 300 ms on this node

        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
        Decision tooSoon = limiter.acquire(rule, "alice", 1);
        assertThat(tooSoon.allowed()).isFalse();
        assertThat(tooSoon.retryAfterMs()).isEqualTo(100);

        clock.advanceMillis(100);
        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
        assertThat(sleeps).containsExactly(200L);
    }

    @Test
    void refusedRequestsReserveNoSlot() {
        var limiter = node();
        Rule rule = TestRules.leakyBucket(1, 3, 3); // 1 a second here, 1 s of waiting at most
        allowed(limiter, rule, 50); // two get slots, at 0 and 1 s

        clock.advanceMillis(1_000);

        assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
        assertThat(sleeps).containsExactly(1_000L, 1_000L);
    }

    @Test
    void anAllowedRequestReportsItsWaitAndItsPlace() {
        var limiter = node();
        Rule rule = TestRules.leakyBucket(1, 30, 30); // 10 a second here, 10 deep

        limiter.acquire(rule, "alice", 1);
        Decision second = limiter.acquire(rule, "alice", 1);

        assertThat(second.waitedMs()).isEqualTo(100);
        assertThat(second.limit()).isEqualTo(10);
        assertThat(second.remaining()).isEqualTo(9);
    }

    @Test
    void aWaitingRequestDoesNotBlockOthersFromBeingDecided() throws InterruptedException {
        var asleep = new CountDownLatch(1);
        var wake = new CountDownLatch(1);
        var limiter = new LocalShareLimiter(() -> 1, 1_000, 100, clock, nanos -> {
            asleep.countDown();
            wake.await();
        });
        Rule rule = TestRules.leakyBucket(1, 1, 1); // one may wait
        limiter.acquire(rule, "alice", 1);
        Thread waiter = Thread.ofPlatform().start(() -> limiter.acquire(rule, "alice", 1));
        asleep.await();

        Decision third = limiter.acquire(rule, "alice", 1); // would hang if the lock were held while waiting

        assertThat(third.reason()).isEqualTo(Reason.QUEUE_FULL);
        wake.countDown();
        assertThat(waiter.join(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void theNodeHoldsNoMoreThanItsMaximumOfWaiters() throws InterruptedException {
        var asleep = new AtomicInteger();
        var wake = new CountDownLatch(1);
        var limiter = new LocalShareLimiter(() -> 1, 1_000, 2, clock, nanos -> {
            asleep.incrementAndGet();
            wake.await();
        });
        Rule rule = TestRules.leakyBucket(1, 10, 100);
        limiter.acquire(rule, "alice", 1); // goes at once
        List<Thread> waiters = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            waiters.add(Thread.ofPlatform().start(() -> limiter.acquire(rule, "alice", 1)));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> asleep.get() == 2);

        assertThat(limiter.acquire(rule, "bob", 1).reason()).isEqualTo(Reason.QUEUE_FULL);

        wake.countDown();
        for (Thread waiter : waiters) {
            assertThat(waiter.join(Duration.ofSeconds(5))).isTrue();
        }
        assertThat(limiter.acquire(rule, "bob", 1).allowed()).as("room again").isTrue();
    }

    @Test
    void anInterruptedWaiterIsTurnedAwayAndStaysInterrupted() {
        var limiter = new LocalShareLimiter(() -> 1, 1_000, 100, clock, nanos -> {
            throw new InterruptedException();
        });
        Rule rule = TestRules.leakyBucket(1, 1, 5);
        limiter.acquire(rule, "alice", 1);

        Decision interrupted = limiter.acquire(rule, "alice", 1);

        assertThat(Thread.interrupted()).isTrue(); // also clears it for the tests that follow
        assertThat(interrupted.allowed()).isFalse();
    }

    @Test
    void withARealClockRequestsReallyAreSpacedOut() {
        var limiter = new LocalShareLimiter(() -> 2, 1_000, 100);
        Rule rule = TestRules.leakyBucket(1, 40, 100); // 20 a second here
        long start = System.nanoTime();

        assertThat(allowed(limiter, rule, 7)).isEqualTo(7);

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isBetween(Duration.ofMillis(295), Duration.ofMillis(800)); // six gaps of 50 ms
    }

    // ---- common ----

    @Test
    void rulesAndKeysAreSeparate() {
        var limiter = node();
        Rule window = TestRules.slidingWindow(1, 3, 1_000);
        limiter.acquire(window, "alice", 1);

        assertThat(limiter.acquire(window, "bob", 1).allowed()).isTrue();
        assertThat(limiter.acquire(TestRules.slidingWindow(2, 3, 1_000), "alice", 1).allowed()).isTrue();
    }

    @Test
    void impossibleCostsAreRefused() {
        var limiter = node();

        assertThatThrownBy(() -> limiter.acquire(TestRules.tokenBucket(1, 5, 1), "a", 0))
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> limiter.acquire(TestRules.tokenBucket(1, 5, 1), "a", 6))
                .hasMessageContaining("exceeds capacity");
        assertThatThrownBy(() -> limiter.acquire(TestRules.slidingWindow(2, 5, 1_000), "a", 2))
                .hasMessageContaining("cost must be 1");
        assertThatThrownBy(() -> limiter.acquire(TestRules.leakyBucket(3, 1, 1), "a", 2))
                .hasMessageContaining("cost must be 1");
    }
}
