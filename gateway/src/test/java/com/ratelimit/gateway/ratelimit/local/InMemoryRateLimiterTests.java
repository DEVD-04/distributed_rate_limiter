package com.ratelimit.gateway.ratelimit.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.ratelimit.FakeNanoClock;
import com.ratelimit.gateway.ratelimit.TestRules;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.gateway.rules.Rule;
import com.ratelimit.gateway.rules.TokenBucketParams;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTests {

    private final FakeNanoClock clock = new FakeNanoClock();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock, 10_000);

    private Decision acquire(Rule rule, String key) {
        return limiter.acquire(rule, key, 1);
    }

    // ---- basics ----

    @Test
    void newBucketStartsFullAndRemainingCountsDown() {
        Rule rule = TestRules.tokenBucket(1, 5, 1);

        for (int expectedRemaining = 4; expectedRemaining >= 0; expectedRemaining--) {
            Decision d = acquire(rule, "alice");
            assertThat(d.allowed()).isTrue();
            assertThat(d.reason()).isEqualTo(Reason.ALLOWED);
            assertThat(d.limit()).isEqualTo(5);
            assertThat(d.remaining()).isEqualTo(expectedRemaining);
            assertThat(d.retryAfterMs()).isZero();
        }
    }

    @Test
    void exactlyCapacityRequestsPassThenTheNextIsRejected() {
        Rule rule = TestRules.tokenBucket(1, 10, 2);
        for (int i = 0; i < 10; i++) {
            assertThat(acquire(rule, "alice").allowed()).as("request %d", i + 1).isTrue();
        }

        Decision rejected = acquire(rule, "alice");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reason()).isEqualTo(Reason.LIMIT_EXCEEDED);
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.limit()).isEqualTo(10);
    }

    @Test
    void resetIsTheTimeUntilTheBucketIsFull() {
        Rule rule = TestRules.tokenBucket(1, 10, 2);
        for (int i = 0; i < 9; i++) {
            acquire(rule, "alice");
        }
        // 9 spent, 1 left; 9 tokens at 2/s = 4.5 s
        assertThat(acquire(rule, "alice").resetMs()).isEqualTo(5000); // now empty: 10 tokens at 2/s
    }

    // ---- refill ----

    @Test
    void retryAfterCountsDownAndTheRequestSucceedsExactlyWhenPromised() {
        Rule rule = TestRules.tokenBucket(1, 10, 2); // one token every 500 ms
        for (int i = 0; i < 10; i++) {
            acquire(rule, "alice");
        }

        assertThat(acquire(rule, "alice").retryAfterMs()).isEqualTo(500);
        clock.advanceMillis(200);
        assertThat(acquire(rule, "alice").retryAfterMs()).isEqualTo(300);
        clock.advanceMillis(299);
        Decision almost = acquire(rule, "alice");
        assertThat(almost.allowed()).isFalse();
        assertThat(almost.retryAfterMs()).isEqualTo(1);
        clock.advanceMillis(1);
        Decision now = acquire(rule, "alice");
        assertThat(now.allowed()).isTrue();
        assertThat(now.remaining()).isZero();
    }

    @Test
    void rejectionNeverReportsZeroRetryAfter() {
        Rule rule = TestRules.tokenBucket(1, 1, 1000); // 1 ms per token
        acquire(rule, "alice");
        clock.advanceNanos(10); // far less than a millisecond

        Decision d = acquire(rule, "alice");

        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterMs()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refillNeverExceedsCapacity() {
        Rule rule = TestRules.tokenBucket(1, 10, 5);
        for (int i = 0; i < 10; i++) {
            acquire(rule, "alice");
        }
        clock.advance(Duration.ofHours(1));

        int admitted = 0;
        while (acquire(rule, "alice").allowed()) {
            admitted++;
        }
        assertThat(admitted).isEqualTo(10);
    }

    @Test
    void aVeryLongIdlePeriodDoesNotOverflow() {
        Rule rule = TestRules.tokenBucket(1, 1_000_000_000L, 1_000_000_000);
        acquire(rule, "alice");
        clock.advance(Duration.ofDays(36_500)); // a century

        assertThat(acquire(rule, "alice").allowed()).isTrue();
    }

    @Test
    void refillIsExactEvenWhenPolledAtAnAwkwardInterval() {
        // Drain a 1,000-token bucket, then poll every 333,333 ns for 300 s at 1 token/s.
        // The bucket is never full again, so nothing is capped; exactly 300 tokens must appear.
        // An implementation that rounds each refill down would lose ~0.33 micro-tokens per poll
        // (~0.3 token over 900k polls) and admit only 299.
        Rule rule = TestRules.tokenBucket(1, 1_000, 1);
        assertThat(limiter.acquire(rule, "alice", 1_000).allowed()).isTrue();

        long interval = 333_333;
        long end = Duration.ofSeconds(300).toNanos();
        int admitted = 0;
        for (long t = 0; t + interval <= end; t += interval) {
            clock.advanceNanos(interval);
            if (acquire(rule, "alice").allowed()) {
                admitted++;
            }
        }
        clock.set(end);
        if (acquire(rule, "alice").allowed()) {
            admitted++;
        }

        assertThat(admitted).isEqualTo(300);
    }

    @Test
    void fractionalRatesRefillOnSchedule() {
        Rule rule = TestRules.tokenBucket(1, 1, 0.5); // one token every 2 s
        int admitted = 0;
        for (int ms = 0; ms <= 60_000; ms++) {
            if (acquire(rule, "alice").allowed()) {
                admitted++;
            }
            clock.advanceMillis(1);
        }
        assertThat(admitted).isEqualTo(31); // the initial token + one at each of 2 s, 4 s ... 60 s
    }

    // ---- isolation ----

    @Test
    void keysAreIndependent() {
        Rule rule = TestRules.tokenBucket(1, 1, 0.001);
        assertThat(acquire(rule, "alice").allowed()).isTrue();
        assertThat(acquire(rule, "alice").allowed()).isFalse();
        assertThat(acquire(rule, "bob").allowed()).isTrue();
    }

    @Test
    void rulesAreIndependentForTheSameKey() {
        Rule images = TestRules.tokenBucket(1, 1, 0.001);
        Rule text = TestRules.tokenBucket(2, 1, 0.001);
        assertThat(acquire(images, "alice").allowed()).isTrue();
        assertThat(acquire(images, "alice").allowed()).isFalse();
        assertThat(acquire(text, "alice").allowed()).isTrue();
    }

    // ---- cost ----

    @Test
    void ruleCostIsChargedPerRequest() {
        Rule rule = TestRules.tokenBucket(1, 10, 1, 2);
        for (int i = 0; i < 5; i++) {
            assertThat(acquire(rule, "alice").allowed()).isTrue();
        }
        assertThat(acquire(rule, "alice").allowed()).isFalse();
    }

    @Test
    void requestCostMultipliesTheRuleCost() {
        Rule rule = TestRules.tokenBucket(1, 10, 1);
        assertThat(limiter.acquire(rule, "alice", 3).remaining()).isEqualTo(7);
        assertThat(limiter.acquire(rule, "alice", 3).remaining()).isEqualTo(4);
        assertThat(limiter.acquire(rule, "alice", 3).remaining()).isEqualTo(1);
        assertThat(limiter.acquire(rule, "alice", 3).allowed()).isFalse();
    }

    @Test
    void aCostThatCanNeverFitIsAnError() {
        Rule rule = TestRules.tokenBucket(1, 10, 1);
        assertThatThrownBy(() -> limiter.acquire(rule, "alice", 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.acquire(rule, "alice", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- live rule changes ----

    @Test
    void loweringCapacityClampsTheExistingBalance() {
        limiter.acquire(TestRules.tokenBucket(1, 10, 0.001), "alice", 1); // 9 left
        Rule smaller = TestRules.tokenBucket(1, 3, 0.001);                  // same rule id, edited

        assertThat(acquire(smaller, "alice").remaining()).isEqualTo(2);
        assertThat(acquire(smaller, "alice").remaining()).isEqualTo(1);
        assertThat(acquire(smaller, "alice").remaining()).isZero();
        assertThat(acquire(smaller, "alice").allowed()).isFalse();
    }

    @Test
    void raisingCapacityDoesNotInstantlyRefill() {
        Rule small = TestRules.tokenBucket(1, 3, 1);
        for (int i = 0; i < 3; i++) {
            acquire(small, "alice");
        }
        Rule bigger = TestRules.tokenBucket(1, 10, 1);

        assertThat(acquire(bigger, "alice").allowed()).isFalse();
        clock.advance(Duration.ofSeconds(5));
        Decision d = acquire(bigger, "alice");
        assertThat(d.allowed()).isTrue();
        assertThat(d.remaining()).isEqualTo(4);
    }

    @Test
    void changingTheRefillRateAppliesFromTheNextCall() {
        Rule slow = TestRules.tokenBucket(1, 10, 1);
        for (int i = 0; i < 10; i++) {
            acquire(slow, "alice");
        }
        Rule fast = TestRules.tokenBucket(1, 10, 10);

        clock.advanceMillis(500);
        assertThat(acquire(fast, "alice").remaining()).isEqualTo(4); // 5 refilled at 10/s, 1 spent
    }

    // ---- memory bounds ----

    private static final long SECOND = 1_000_000_000L;

    @Test
    void idleLifetimeIsTwiceTheFullRefillTimePlusAMargin() {
        assertThat(InMemoryRateLimiter.idleTtlNanos(new TokenBucketParams(10, 10))).isEqualTo(3 * SECOND);
        assertThat(InMemoryRateLimiter.idleTtlNanos(new TokenBucketParams(100, 1))).isEqualTo(201 * SECOND);
        assertThat(InMemoryRateLimiter.idleTtlNanos(new TokenBucketParams(10, 0.000001)))
                .isEqualTo(Duration.ofDays(30).toNanos()); // 2 * 10^7 s would be 231 days: capped
    }

    @Test
    void anUnusedBucketCountsDownToDropAndUseRestartsTheCountdown() {
        Rule rule = TestRules.tokenBucket(1, 10, 10); // idle lifetime 3 s
        acquire(rule, "alice");
        assertThat(limiter.idleTimeRemainingNanos(rule, "alice")).hasValue(3 * SECOND);

        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.idleTimeRemainingNanos(rule, "alice")).hasValue(1 * SECOND);

        acquire(rule, "alice");
        assertThat(limiter.idleTimeRemainingNanos(rule, "alice")).hasValue(3 * SECOND);
    }

    @Test
    void anUnknownBucketHasNoIdleTime() {
        assertThat(limiter.idleTimeRemainingNanos(TestRules.tokenBucket(1, 10, 10), "nobody")).isEmpty();
    }

    @Test
    void theNumberOfBucketsIsBounded() {
        InMemoryRateLimiter small = new InMemoryRateLimiter(clock, 100);
        Rule rule = TestRules.tokenBucket(1, 5, 1);
        for (int i = 0; i < 1_000; i++) {
            small.acquire(rule, "ip-" + i, 1);
        }
        small.cleanUp();
        assertThat(small.activeKeys()).isLessThanOrEqualTo(100);
    }

    @Test
    void editingARuleExtendsHowLongIdleBucketsAreKept() {
        Rule quick = TestRules.tokenBucket(1, 1, 1);        // full refill 1 s, idle lifetime 3 s
        acquire(quick, "alice");
        assertThat(limiter.idleTimeRemainingNanos(quick, "alice")).hasValue(3 * SECOND);

        Rule slowToFill = TestRules.tokenBucket(1, 100, 1); // full refill 100 s, idle lifetime 201 s
        acquire(slowToFill, "alice");

        // Without following the edit the bucket would still be dropped after 3 s, i.e. before
        // it had refilled to the new, larger capacity.
        assertThat(limiter.idleTimeRemainingNanos(slowToFill, "alice")).hasValue(201 * SECOND);
    }
}
