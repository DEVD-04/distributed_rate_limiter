package com.ratelimit.gateway.ratelimit.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.gateway.redis.RedisSkippedException;
import com.ratelimit.rules.Rule;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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

    private static final Duration HORIZON = Duration.ofSeconds(1);

    /** Background refills wait here until a test runs them. */
    private final Deque<Runnable> refills = new ArrayDeque<>();

    /** A limiter that never refills in the background. */
    private LocalSyncLimiter limiter(long batch) {
        return build(shared, batch, 0, refills::add);
    }

    /** A limiter that refills below 30% of a batch, as in normal use. */
    private LocalSyncLimiter refilling(long batch) {
        return build(shared, batch, 0.3, refills::add);
    }

    private LocalSyncLimiter build(LeaseSource source, long batch, double refillBelow, Executor background) {
        return new LocalSyncLimiter(source, nodes::get, fallback, new LeaseSettings(batch, HORIZON, refillBelow),
                1_000, background, clock);
    }

    private void runRefills() {
        while (!refills.isEmpty()) {
            refills.poll().run();
        }
    }

    private FakeSharedBucket.Call lastCall() {
        return shared.calls.get(shared.calls.size() - 1);
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
    void batchesGrowWhenManyRequestsComeAtOnce() {
        assertThat(allowed(limiter(20), RULE, "alice", 100)).isEqualTo(100);

        // each call asks for as much as has recently been wanted; the clock stands still, so nothing fades
        assertThat(shared.calls).extracting(FakeSharedBucket.Call::want).containsExactly(20L, 21L, 42L, 84L);
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
        shared.fullMs = 1_500;
        assertThat(limiter.acquire(RULE, "alice", 1).resetMs()).isEqualTo(1_500);

        clock.advanceMillis(1_000);
        assertThat(limiter.acquire(RULE, "alice", 1).resetMs()).isEqualTo(500);

        clock.advanceMillis(900);
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
        // the second call asks for what has been wanted: all twenty
        assertThat(shared.calls).extracting(FakeSharedBucket.Call::want).containsExactly(5L, 20L);
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
        clock.advance(Duration.ofMinutes(1)); // recent demand has faded, so only waiters could raise the batch
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
        var limiter = build(breaksOnce, 5, 0, refills::add);
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
        assertThatThrownBy(() -> new LeaseSettings(0, HORIZON, 0.3)).hasMessageContaining("minimum batch");
        assertThatThrownBy(() -> new LeaseSettings(1, Duration.ZERO, 0.3)).hasMessageContaining("horizon");
        assertThatThrownBy(() -> new LeaseSettings(1, Duration.ofHours(2), 0.3)).hasMessageContaining("horizon");
        assertThatThrownBy(() -> new LeaseSettings(1, HORIZON, 1)).hasMessageContaining("refill-below");
        assertThatThrownBy(() -> new LeaseSettings(1, HORIZON, -0.1)).hasMessageContaining("refill-below");
        assertThatThrownBy(() -> new LeaseSettings(1, HORIZON, Double.NaN)).hasMessageContaining("refill-below");
    }

    // ---- borrowed tokens expire ----

    @Test
    void unspentTokensExpireAfterTwoHorizonsAndGoBackWithTheNextCall() {
        var limiter = limiter(10);
        limiter.acquire(RULE, "alice", 1);

        clock.advanceMillis(1_999);
        limiter.acquire(RULE, "alice", 1);
        assertThat(shared.calls).as("still spendable just before").hasSize(1);

        clock.advanceMillis(1);
        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isTrue();
        assertThat(shared.calls).hasSize(2);
        assertThat(lastCall().returned()).isEqualTo(8);
        // 100 = 3 spent + what the node holds (the new grant less 1) + what is left
        long granted = 100 - 10 + 8 - shared.left(ID, "alice");
        assertThat(allowed(limiter, RULE, "alice", (int) granted - 1)).isEqualTo(granted - 1);
        assertThat(shared.calls).as("nothing lost, nothing made").hasSize(2);
    }

    @Test
    void theTokensOfAFailedCallAreNeverSentAgain() {
        var limiter = limiter(10);
        limiter.acquire(RULE, "alice", 1);
        clock.advanceMillis(2_000);
        shared.failure = new IllegalStateException("no reply");

        assertThat(limiter.acquire(RULE, "alice", 1)).isEqualTo(FALLBACK_ANSWER);
        assertThat(lastCall().returned()).isEqualTo(9);

        shared.failure = null;
        limiter.acquire(RULE, "alice", 1);
        // the failed call may have reached the bucket: sending the 9 again could count them twice
        assertThat(lastCall().returned()).isZero();
    }

    @Test
    void tokensOfACallThatWasNeverMadeGoWithTheNextOne() {
        var limiter = limiter(10);
        limiter.acquire(RULE, "alice", 1);
        clock.advanceMillis(2_000);
        shared.failure = new RedisSkippedException(); // the circuit breaker is open

        assertThat(limiter.acquire(RULE, "alice", 1)).isEqualTo(FALLBACK_ANSWER);
        assertThat(limiter.acquire(RULE, "alice", 1)).isEqualTo(FALLBACK_ANSWER);
        assertThat(lastCall().returned()).isEqualTo(9);

        shared.failure = null;
        limiter.acquire(RULE, "alice", 1);
        assertThat(lastCall().returned()).as("never sent, so still to be given back").isEqualTo(9);
        // and once only
        clock.advance(Duration.ofMinutes(1));
        long held = lastCall().want();
        limiter.acquire(RULE, "alice", 1);
        assertThat(lastCall().returned()).isEqualTo(held - 1);
    }

    @Test
    void expiredTokensCanBeBorrowedStraightBack() {
        var small = new FakeSharedBucket(10);
        var limiter = build(small, 10, 0, refills::add);
        limiter.acquire(RULE, "alice", 1); // the node now holds all that is left

        clock.advanceMillis(3_000);

        assertThat(limiter.acquire(RULE, "alice", 1).allowed()).isTrue();
        assertThat(small.calls.get(1).returned()).isEqualTo(9);
    }

    @Test
    void expiredTokensAreGivenBackEvenWhileTheSharedBucketIsBelievedEmpty() {
        var small = new FakeSharedBucket(10);
        var limiter = build(small, 10, 0, refills::add);
        Rule costly = TestRules.tokenBucket(ID, 100, 1, 4);
        // holds 2; the third is refused from memory, as the first reply already said there was no more
        assertThat(allowed(limiter, costly, "alice", 3)).isEqualTo(2);
        clock.advanceMillis(1_500);
        limiter.acquire(costly, "alice", 1); // asks again, and is now not to ask before 2.5 s
        assertThat(small.calls).hasSize(2);

        clock.advanceMillis(500); // the 2 expire, well within that wait
        limiter.acquire(costly, "alice", 1);

        assertThat(small.calls).hasSize(3);
        assertThat(small.calls.get(2).returned()).isEqualTo(2);
        assertThat(small.left(ID, "alice")).isEqualTo(2);
    }

    @Test
    void aLateRefillCannotGiveExpiredTokensANewLife() {
        var limiter = refilling(10);
        allowed(limiter, RULE, "alice", 8); // 2 left, and a refill is on its way
        assertThat(refills).hasSize(1);
        clock.advanceMillis(2_000); // the reply is very late: the 2 have expired meanwhile

        runRefills();
        long granted = 100 - 10 - shared.left(ID, "alice");
        limiter.acquire(RULE, "alice", 1);
        assertThat(shared.calls).as("served from the new batch").hasSize(2);

        clock.advanceMillis(2_000);
        limiter.acquire(RULE, "alice", 1);

        assertThat(lastCall().returned()).isEqualTo(2 + granted - 1);
    }

    // ---- the rule changes ----

    @Test
    void afterALimitIsLoweredTheTokensBorrowedBeforeAreNeitherSpentNorGivenBack() {
        var big = new FakeSharedBucket(1_000);
        var limiter = build(big, 100, 0, refills::add);
        limiter.acquire(TestRules.tokenBucket(ID, 1_000, 1), "alice", 1); // holds 99

        Rule lowered = TestRules.tokenBucket(ID, 5, 1);

        assertThat(allowed(limiter, lowered, "alice", 200)).as("the new limit, nothing on top").isEqualTo(5);
        assertThat(big.calls.get(1).returned()).isZero();
    }

    @Test
    void afterALimitIsLoweredSeveralNodesTogetherGetOnlyTheNewLimit() {
        var big = new FakeSharedBucket(1_000);
        nodes.set(3);
        var three = List.of(build(big, 100, 0, refills::add), build(big, 100, 0, refills::add),
                build(big, 100, 0, refills::add));
        Rule generous = TestRules.tokenBucket(ID, 1_000, 1);
        three.forEach(node -> node.acquire(generous, "alice", 1)); // each is now holding 99

        Rule lowered = TestRules.tokenBucket(ID, 5, 1);
        long allowed = 0;
        for (int i = 0; i < 90; i++) {
            if (three.get(i % 3).acquire(lowered, "alice", 1).allowed()) {
                allowed++;
            }
        }

        // had each node given its old tokens back, every return would have topped the bucket up to 5 again
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void expiredTokensAwaitingReturnAreDroppedWhenTheRuleChanges() {
        var small = new FakeSharedBucket(10);
        var limiter = build(small, 10, 0, refills::add);
        Rule costly = TestRules.tokenBucket(ID, 100, 1, 4);
        allowed(limiter, costly, "alice", 2); // holds 2
        small.failure = new IllegalStateException("never answered");
        clock.advanceMillis(1_500);
        limiter.acquire(costly, "alice", 1);
        small.failure = null;
        clock.advanceMillis(500); // the 2 expire and now wait to be returned

        limiter.acquire(TestRules.tokenBucket(ID, 50, 1), "alice", 1);

        assertThat(small.calls.get(small.calls.size() - 1).returned()).isZero();
    }

    @Test
    void aChangedRuleIsAskedAboutAtOnceEvenIfTheSharedBucketWasEmpty() {
        var limiter = limiter(100);
        shared.waitMsWhenShort = 60_000;
        assertThat(allowed(limiter, RULE, "alice", 101)).isEqualTo(100);
        shared.add(ID, "alice", 50); // the new refill rate has already put tokens back

        assertThat(limiter.acquire(TestRules.tokenBucket(ID, 100, 1_000), "alice", 1).allowed()).isTrue();
    }

    @Test
    void anEqualRuleReadAgainChangesNothing() {
        var limiter = limiter(10);
        limiter.acquire(RULE, "alice", 1);

        limiter.acquire(TestRules.tokenBucket(ID, 100, 1), "alice", 1); // another object, the same parameters

        assertThat(shared.calls).hasSize(1);
    }

    @Test
    void aReplyToACallMadeUnderTheOldRuleIsThrownAway() throws InterruptedException {
        var limiter = refilling(10);
        allowed(limiter, RULE, "alice", 8); // 2 left, and a refill under the old rule is on its way
        Rule changed = TestRules.tokenBucket(ID, 50, 1);
        var decisions = new ArrayList<Decision>();
        Thread underNewRule = Thread.ofPlatform().start(() -> decisions.add(limiter.acquire(changed, "alice", 1)));
        await().atMost(Duration.ofSeconds(5)).until(() -> underNewRule.getState() == Thread.State.WAITING);

        refills.poll().run(); // only the old rule's refill: its reply arrives after the change
        assertThat(underNewRule.join(Duration.ofSeconds(5))).isTrue();

        assertThat(decisions).hasSize(1).allMatch(Decision::allowed);
        // it did not make do with the late grant: it borrowed for itself, and gave neither that nor the 2 back
        assertThat(shared.calls).hasSize(3);
        assertThat(lastCall().need()).isEqualTo(1);
        assertThat(lastCall().returned()).isZero();
    }

    // ---- batches follow demand ----

    /** One request every {@code gapMs}, with any refill finishing before the next. */
    private void steadyTraffic(RateLimiter limiter, Rule rule, int requests, long gapMs) {
        for (int i = 0; i < requests; i++) {
            assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
            runRefills();
            clock.advanceMillis(gapMs);
        }
    }

    @Test
    void aSteadyKeyBorrowsAboutOneHorizonOfDemandAndBatchesShrinkWhenItSlows() {
        var big = new FakeSharedBucket(1_000_000);
        var limiter = build(big, 1, 0.3, refills::add);
        Rule rule = TestRules.tokenBucket(ID, 1_000_000, 1);

        steadyTraffic(limiter, rule, 1_000, 10); // 100 requests a second for 10 s

        FakeSharedBucket.Call busy = big.calls.get(big.calls.size() - 1);
        assertThat(busy.want()).isBetween(95L, 105L);
        assertThat(big.calls).as("1,000 requests").hasSizeLessThan(30);

        clock.advance(Duration.ofSeconds(30));
        limiter.acquire(rule, "alice", 1);
        FakeSharedBucket.Call quiet = big.calls.get(big.calls.size() - 1);
        assertThat(quiet.want()).isEqualTo(1);
        assertThat(quiet.returned()).as("what the busy spell left over").isPositive();
    }

    @Test
    void aQuietKeyCostsOneCallPerRequestAndHoardsNothing() {
        var limiter = refilling(1);

        steadyTraffic(limiter, RULE, 5, 10_000);

        assertThat(shared.calls).hasSize(5).allMatch(call -> call.want() == 1 && call.returned() == 0);
        assertThat(shared.left(ID, "alice")).isEqualTo(95);
    }

    // ---- refilling in the background ----

    @Test
    void belowThirtyPercentOneRefillStartsAndNoRequestWaitsForIt() {
        var limiter = refilling(10);

        allowed(limiter, RULE, "alice", 7);
        assertThat(refills).as("3 of 10 left is not below 30%").isEmpty();

        assertThat(allowed(limiter, RULE, "alice", 3)).isEqualTo(3);
        assertThat(refills).as("one, however many requests follow").hasSize(1);
        assertThat(shared.calls).as("it has not even run yet").hasSize(1);

        runRefills();
        assertThat(lastCall().need()).as("nobody is waiting").isZero();
        assertThat(allowed(limiter, RULE, "alice", 5)).isEqualTo(5);
        assertThat(shared.calls).hasSize(2);
    }

    @Test
    void aBusyKeyStopsWaitingOnceRefillsKeepUp() {
        var big = new FakeSharedBucket(1_000_000);
        var limiter = build(big, 1, 0.3, refills::add);
        Rule rule = TestRules.tokenBucket(ID, 1_000_000, 1);
        int waited = 0;

        for (int i = 0; i < 500; i++) {
            int before = big.calls.size();
            assertThat(limiter.acquire(rule, "alice", 1).allowed()).isTrue();
            waited += big.calls.size() - before; // a call made inside acquire() is one the request waited for
            runRefills();
            clock.advanceMillis(10);
        }

        assertThat(waited).as("only while warming up").isLessThanOrEqualTo(2);
    }

    @Test
    void noRefillStartsWhileTheSharedBucketIsEmpty() {
        var small = new FakeSharedBucket(10);
        var limiter = build(small, 10, 0.3, refills::add);

        allowed(limiter, RULE, "alice", 10);

        assertThat(refills).isEmpty();
    }

    @Test
    void aFailedRefillKeepsTheTokensInHandAndPausesBeforeTryingAgain() {
        var big = new FakeSharedBucket(1_000);
        var limiter = build(big, 100, 0.3, refills::add);
        Rule rule = TestRules.tokenBucket(ID, 1_000, 1);
        allowed(limiter, rule, "alice", 71); // 29 left: a refill starts
        big.failure = new IllegalStateException("redis is away");
        runRefills();

        assertThat(allowed(limiter, rule, "alice", 10)).isEqualTo(10);
        assertThat(refills).as("not again at once").isEmpty();
        assertThat(fallbackCalls).hasValue(0);

        clock.advanceMillis(1_000);
        limiter.acquire(rule, "alice", 1);
        assertThat(refills).hasSize(1);
    }

    @Test
    void requestsThatRunOutDuringARefillWaitForIt() throws InterruptedException {
        var limiter = build(shared, 10, 0.3, task -> Thread.ofPlatform().start(task));
        limiter.acquire(RULE, "alice", 1);
        shared.gate = new CountDownLatch(1); // holds the refill
        assertThat(allowed(limiter, RULE, "alice", 9)).isEqualTo(9);
        await().atMost(Duration.ofSeconds(5)).until(() -> shared.calls.size() == 2);

        var decisions = new ArrayList<Decision>();
        List<Thread> threads = startTogether(limiter, 5, decisions);
        assertThat(shared.calls).as("they join the refill").hasSize(2);
        shared.gate.countDown();
        finish(threads);

        assertThat(decisions).hasSize(5).allMatch(Decision::allowed);
        assertThat(shared.calls).hasSize(2);
    }

    @Test
    void aRefusedRefillTaskLeavesNothingStuck() {
        var limiter = build(shared, 10, 0.3, task -> {
            throw new RejectedExecutionException("shutting down");
        });

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThat(allowed(limiter, RULE, "alice", 30)).isEqualTo(30));
    }
}
