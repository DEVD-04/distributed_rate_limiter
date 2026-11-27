package com.ratelimit.gateway.ratelimit.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.rules.Rule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The node's side of leasing, with a fake shared bucket and a clock the test moves. */
class LocalSyncLimiterTests {

    private static final long ID = 7;
    private static final Rule RULE = TestRules.tokenBucket(ID, 100, 1);
    private static final Decision FALLBACK_ANSWER = Decision.allowed(1, 1, 1);

    private final FakeNanoClock clock = new FakeNanoClock();
    private final FakeSharedBucket shared = new FakeSharedBucket(100);
    private final AtomicInteger nodes = new AtomicInteger(1);
    private final AtomicInteger fallbackCalls = new AtomicInteger();
    private final RateLimiter fallback = (rule, key, cost) -> {
        fallbackCalls.incrementAndGet();
        return FALLBACK_ANSWER;
    };

    private LocalSyncLimiter limiter(long batch) {
        return new LocalSyncLimiter(shared, nodes::get, fallback, batch, Duration.ofSeconds(1), 1_000, clock);
    }

    /** @return how many of {@code requests} requests were allowed */
    private static long allowed(RateLimiter limiter, Rule rule, String key, int requests) {
        long allowed = 0;
        for (int i = 0; i < requests; i++) {
            if (limiter.acquire(rule, key, 1).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    // ---- spending from memory ----

    @Test
    void theFirstRequestBorrowsABatchAndIsServedFromIt() {
        var limiter = limiter(10);

        Decision first = limiter.acquire(RULE, "alice", 1);

        assertThat(first.allowed()).isTrue();
        assertThat(shared.calls).containsExactly(new FakeSharedBucket.Call(ID, "alice", 10, 1, 0, 1));
    }

    @Test
    void theRestOfTheBatchIsSpentWithoutAnyCall() {
        var limiter = limiter(10);

        assertThat(allowed(limiter, RULE, "alice", 10)).isEqualTo(10);
        assertThat(shared.calls).hasSize(1);

        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isTrue();
        assertThat(shared.calls).hasSize(2);
    }

    @Test
    void aHundredRequestsInBatchesOfTwentyMakeFiveCalls() {
        assertThat(allowed(limiter(20), RULE, "alice", 100)).isEqualTo(100);

        assertThat(shared.calls).hasSize(5);
    }

    @Test
    void eachRuleAndKeyBorrowsForItself() {
        var limiter = limiter(10);

        limiter.acquire(RULE, "alice", 1);
        limiter.acquire(RULE, "bob", 1);
        limiter.acquire(TestRules.tokenBucket(8, 100, 1), "alice", 1);

        assertThat(shared.calls).hasSize(3);
    }

    @Test
    void theCurrentNodeCountSetsTheShare() {
        var limiter = limiter(100);
        nodes.set(4);

        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isTrue();

        assertThat(shared.calls.get(0).nodeCount()).isEqualTo(4);
        assertThat(shared.left(ID, "alice")).isEqualTo(75);
    }

    @Test
    void aBatchIsNeverBiggerThanTheCapacity() {
        limiter(1_000).acquire(TestRules.tokenBucket(ID, 30, 1), "alice", 1);

        assertThat(shared.calls.get(0).want()).isEqualTo(30);
    }

    // ---- an empty shared bucket ----

    @Test
    void onceTheSharedBucketRunsOutRequestsAreRefusedFromMemory() {
        var limiter = limiter(100);
        assertThat(allowed(limiter, RULE, "alice", 100)).isEqualTo(100);

        for (int i = 0; i < 1_000; i++) {
            Decision d = limiter.acquire(RULE, "alice", 1);
            assertThat(d.allowed()).isFalse();
            assertThat(d.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
            assertThat(d.retryAfterMs()).isEqualTo(5_000);
        }
        // the one grant said the bucket was then empty, so not even the first refusal needed to ask
        assertThat(shared.calls).hasSize(1);
    }

    @Test
    void theRetryAfterCountsDown() {
        var limiter = limiter(100);
        allowed(limiter, RULE, "alice", 100);

        clock.advanceMillis(400);

        assertThat(limiter.acquire(RULE, "alice", 1).retryAfterMs()).isEqualTo(4_600);
    }

    @Test
    void aLongWaitIsStillCheckedEveryRecheckIntervalBecauseTokensMayComeBack() {
        var limiter = limiter(100);
        shared.waitMsWhenShort = 60_000;
        allowed(limiter, RULE, "alice", 101);

        clock.advanceMillis(999);
        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isFalse();
        assertThat(shared.calls).hasSize(1);

        clock.advanceMillis(1);
        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isFalse();
        assertThat(shared.calls).hasSize(2);

        shared.add(ID, "alice", 3); // another node gave tokens back
        clock.advanceMillis(1_000);
        assertThat(allowed(limiter, RULE, "alice", 10)).isEqualTo(3);
    }

    @Test
    void aShortWaitIsAskedAgainAsSoonAsItIsOver() {
        var limiter = limiter(100);
        shared.waitMsWhenShort = 200;
        allowed(limiter, RULE, "alice", 101);
        shared.add(ID, "alice", 1);

        clock.advanceMillis(199);
        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isFalse();

        clock.advanceMillis(1);
        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isTrue();
    }

    @Test
    void anAbsurdlyLongWaitDoesNotOverflow() {
        var limiter = limiter(100);
        shared.waitMsWhenShort = Long.MAX_VALUE;
        shared.fullMs = Long.MAX_VALUE;

        allowed(limiter, RULE, "alice", 100);
        Decision refused = limiter.acquire(RULE, "alice", 1);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMs()).isEqualTo(Duration.ofDays(30).toMillis());
        assertThat(refused.resetMs()).isEqualTo(Duration.ofDays(30).toMillis());
    }

    // ---- requests costing more than one token ----

    @Test
    void tokensForACostlyRequestCollectOnTheNode() {
        var limiter = limiter(1);
        Rule costly = TestRules.tokenBucket(ID, 100, 1, 5);

        assertThat(limiter.acquire(costly, "alice", 1).allowed()).isTrue();
        assertThat(shared.calls.get(0)).isEqualTo(new FakeSharedBucket.Call(ID, "alice", 5, 5, 0, 1));
    }

    @Test
    void onlyTheShortfallIsAskedFor() {
        var limiter = limiter(8);
        Rule costly = TestRules.tokenBucket(ID, 100, 1, 5);
        limiter.acquire(costly, "alice", 1); // borrows 8, spends 5

        assertThat(limiter.acquire(costly, "alice", 1).allowed()).isTrue();

        assertThat(shared.calls.get(1).need()).isEqualTo(2);
    }

    // ---- what the caller is told ----

    @Test
    void remainingCountsTheNodesTokensAndTheSharedOnes() {
        var limiter = limiter(10);

        assertThat(limiter.acquire(RULE, "alice", 1).remaining()).isEqualTo(99);
        assertThat(limiter.acquire(RULE, "alice", 1).remaining()).isEqualTo(98);
        assertThat(limiter.acquire(RULE, "alice", 1).limit()).isEqualTo(100);
    }

    @Test
    void resetCountsDownToWhenTheSharedBucketWasExpectedToBeFull() {
        var limiter = limiter(10);
        assertThat(limiter.acquire(RULE, "alice", 1).resetMs()).isEqualTo(20_000);

        clock.advanceMillis(15_000);
        assertThat(limiter.acquire(RULE, "alice", 1).resetMs()).isEqualTo(5_000);

        clock.advanceMillis(15_000);
        assertThat(limiter.acquire(RULE, "alice", 1).resetMs()).isZero();
    }

    // ---- requests arriving together ----

    /** Starts requests that all have to wait, and returns once every one of them is waiting. */
    private static List<Thread> startTogether(RateLimiter limiter, int requests, List<Decision> decisions) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                Decision d = limiter.acquire(RULE, "alice", 1);
                synchronized (decisions) {
                    decisions.add(d);
                }
            }));
        }
        await().atMost(Duration.ofSeconds(5))
                .until(() -> threads.stream().allMatch(t -> t.getState() == Thread.State.WAITING));
        return threads;
    }

    private static void finish(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            assertThat(thread.join(Duration.ofSeconds(5))).as("no request is left waiting").isTrue();
        }
    }

    @Test
    void requestsArrivingTogetherShareOneCallAndTheNextOneCoversThoseStillWaiting() throws InterruptedException {
        var limiter = limiter(5);
        var decisions = new ArrayList<Decision>();
        shared.gate = new CountDownLatch(1);

        List<Thread> threads = startTogether(limiter, 20, decisions);
        assertThat(shared.calls).as("one call while twenty wait").hasSize(1);
        shared.gate.countDown();
        finish(threads);

        assertThat(decisions).hasSize(20).allMatch(Decision::allowed);
        assertThat(shared.calls).extracting(FakeSharedBucket.Call::want).containsExactly(5L, 15L);
    }

    @Test
    void whenTheSharedBucketRunsShortTheWaitersAreRefusedWithoutMoreCalls() throws InterruptedException {
        var limiter = limiter(5);
        var decisions = new ArrayList<Decision>();
        shared.add(ID, "alice", -97); // three left
        shared.gate = new CountDownLatch(1);

        List<Thread> threads = startTogether(limiter, 20, decisions);
        shared.gate.countDown();
        finish(threads);

        assertThat(decisions.stream().filter(Decision::allowed)).hasSize(3);
        assertThat(decisions.stream().filter(d -> !d.allowed())).hasSize(17);
        assertThat(shared.calls).hasSize(1);
    }

    @Test
    void ifTheCallFailsEveryWaiterUsesTheFallbackAndTheNextRequestTriesAgain() throws InterruptedException {
        var limiter = limiter(5);
        var decisions = new ArrayList<Decision>();
        shared.failure = new IllegalStateException("redis is away");
        shared.gate = new CountDownLatch(1);

        List<Thread> threads = startTogether(limiter, 20, decisions);
        shared.gate.countDown();
        finish(threads);

        assertThat(decisions).hasSize(20).containsOnly(FALLBACK_ANSWER);
        assertThat(fallbackCalls).hasValue(20);
        assertThat(shared.calls).as("one failure, not twenty in a row").hasSize(1);

        shared.failure = null;
        shared.gate = null;
        assertThat(limiter.acquire(RULE, "alice", 1)).isNotEqualTo(FALLBACK_ANSWER);
        assertThat(shared.calls).hasSize(2);
        assertThat(shared.calls.get(1).want()).as("nobody is still counted as waiting").isEqualTo(5);
    }

    @Test
    void evenAnErrorDuringTheCallLeavesNobodyWaiting() throws InterruptedException {
        var failing = new AtomicInteger();
        var release = new CountDownLatch(1);
        LeaseSource breaksOnce = (ruleId, subject, params, want, need, returned, n) -> {
            if (failing.getAndIncrement() == 0) {
                try {
                    release.await(); // untimed, like the other waits startTogether looks for
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                throw new AssertionError("boom");
            }
            return shared.lease(ruleId, subject, params, want, need, returned, n);
        };
        var limiter = new LocalSyncLimiter(breaksOnce, nodes::get, fallback, 5, Duration.ofSeconds(1), 1_000, clock);
        var decisions = new ArrayList<Decision>();

        List<Thread> threads = startTogether(limiter, 5, decisions);
        release.countDown();
        finish(threads);

        // the thread that made the call dies with the error; the others are answered by the fallback
        assertThat(decisions).hasSize(4).containsOnly(FALLBACK_ANSWER);
        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isTrue();
    }

    // ---- when the shared bucket cannot be reached ----

    @Test
    void borrowedTokensAreSpentBeforeTheFallbackIsUsed() {
        var limiter = limiter(10);
        limiter.acquire(RULE, "alice", 1);
        shared.failure = new IllegalStateException("redis is away");

        for (int i = 0; i < 9; i++) {
            assertThat(limiter.acquire(RULE, "alice", 1)).isNotEqualTo(FALLBACK_ANSWER);
        }
        assertThat(fallbackCalls).hasValue(0);

        assertThat(limiter.acquire(RULE, "alice", 1)).isEqualTo(FALLBACK_ANSWER);
    }

    // ---- bad input ----

    @Test
    void impossibleRequestsAreRefusedBeforeAnyCall() {
        var limiter = limiter(10);

        assertThatThrownBy(() -> limiter.acquire(RULE, "a", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.acquire(RULE, "a", 101)).hasMessageContaining("exceeds capacity");
        assertThatThrownBy(() -> limiter.acquire(TestRules.slidingWindow(9, 5, 1_000), "a", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(shared.calls).isEmpty();
    }

    @Test
    void impossibleSettingsAreRefused() {
        assertThatThrownBy(() -> limiter(0)).hasMessageContaining("batch size");
        assertThatThrownBy(() -> new LocalSyncLimiter(shared, nodes::get, fallback, 1, Duration.ZERO, 10, clock))
                .hasMessageContaining("recheck");
    }
}
