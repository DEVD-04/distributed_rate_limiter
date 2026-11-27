package com.ratelimit.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.api.Reason;
import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.redis.RedisBreaker;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LeakyBucketParams;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Params;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.SlidingWindowParams;
import com.ratelimit.rules.TokenBucketParams;
import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FailureModeLimiterTests {

    private static final Decision FROM_LOCAL_SHARE = Decision.allowed(7, 6, 0);

    private final List<String> sharedRequests = new ArrayList<>();
    private final AtomicReference<Duration> retryAfter = new AtomicReference<>(Duration.ofSeconds(5));
    private final RateLimiter localShare = (rule, key, cost) -> {
        sharedRequests.add(rule.name() + " " + key + " " + cost);
        return FROM_LOCAL_SHARE;
    };
    private final FailureModeLimiter onFailure = new FailureModeLimiter(localShare, retryAfter::get);

    private static Rule rule(FailureMode failureMode, Algorithm algorithm) {
        Params params = switch (algorithm) {
            case TOKEN_BUCKET -> new TokenBucketParams(100, 1);
            case SLIDING_WINDOW_LOG -> new SlidingWindowParams(20, 1_000);
            case LEAKY_BUCKET_QUEUE -> new LeakyBucketParams(10, 5);
        };
        return new Rule(1, "r", LimitBy.USER, null, null, "/api/**", null, algorithm, Mode.STRICT, failureMode, params,
                0, 1);
    }

    private static long limitOf(Algorithm algorithm) {
        return switch (algorithm) {
            case TOKEN_BUCKET -> 100;
            case SLIDING_WINDOW_LOG -> 20;
            case LEAKY_BUCKET_QUEUE -> 5;
        };
    }

    @Test
    void failOpenLetsEveryRequestThroughForEveryAlgorithm() {
        for (Algorithm algorithm : Algorithm.values()) {
            for (int i = 0; i < 500; i++) {
                Decision d = onFailure.acquire(rule(FailureMode.FAIL_OPEN, algorithm), "alice", 1);
                assertThat(d.allowed()).isTrue();
                assertThat(d.reason()).isEqualTo(Reason.LIMITER_UNAVAILABLE);
                assertThat(d.limit()).isEqualTo(limitOf(algorithm));
            }
        }
        assertThat(sharedRequests).isEmpty();
    }

    @Test
    void failClosedRefusesEveryRequestAndSaysWhenToRetry() {
        for (Algorithm algorithm : Algorithm.values()) {
            Decision d = onFailure.acquire(rule(FailureMode.FAIL_CLOSED, algorithm), "alice", 1);

            assertThat(d.allowed()).isFalse();
            assertThat(d.reason()).isEqualTo(Reason.FAILED_CLOSED);
            assertThat(d.retryAfterMs()).isEqualTo(5_000);
            assertThat(d.limit()).isEqualTo(limitOf(algorithm));
            assertThat(d.remaining()).isZero();
        }
        retryAfter.set(Duration.ZERO);
        assertThat(onFailure.acquire(rule(FailureMode.FAIL_CLOSED, Algorithm.TOKEN_BUCKET), "alice", 1).retryAfterMs())
                .as("never told to retry in 0 ms").isEqualTo(1);
        assertThat(sharedRequests).isEmpty();
    }

    @Test
    void localFallbackAsksTheNodesShare() {
        Decision d = onFailure.acquire(rule(FailureMode.LOCAL_FALLBACK, Algorithm.SLIDING_WINDOW_LOG), "alice", 1);

        assertThat(d).isSameAs(FROM_LOCAL_SHARE);
        assertThat(sharedRequests).containsExactly("r alice 1");
    }

    // ---- STRICT hands failed calls here ----

    private static StrictRedisLimiter strict(Semaphore waiting, RateLimiter onFailure) {
        return new StrictRedisLimiter(() -> {
            throw new RedisConnectionException("down");
        }, waiting, RedisBreaker.disabled(), onFailure);
    }

    @Test
    void aFailedStrictCallOfAnyAlgorithmIsDecidedByTheFailureLimiter() {
        var strict = strict(new Semaphore(10), onFailure);

        for (Algorithm algorithm : Algorithm.values()) {
            assertThat(strict.acquire(rule(FailureMode.LOCAL_FALLBACK, algorithm), "alice", 1))
                    .isSameAs(FROM_LOCAL_SHARE);
        }

        assertThat(sharedRequests).hasSize(3);
    }

    @Test
    void theLeakyBucketGivesUpItsWaitingPermitBeforeAskingTheFailureLimiter() {
        // one permit in all, shared: if STRICT still held it, a fallback that queues would find none
        var waiting = new Semaphore(1);
        var permitsSeen = new ArrayList<Integer>();
        var strict = strict(waiting, (r, key, cost) -> {
            permitsSeen.add(waiting.availablePermits());
            return FROM_LOCAL_SHARE;
        });

        strict.acquire(rule(FailureMode.LOCAL_FALLBACK, Algorithm.LEAKY_BUCKET_QUEUE), "alice", 1);

        assertThat(permitsSeen).containsExactly(1);
        assertThat(waiting.availablePermits()).isEqualTo(1);
    }

    @Test
    void aSkippedCallIsDecidedTheSameWay() {
        var breaker = new RedisBreaker(new com.ratelimit.gateway.redis.RedisBreakerProperties(
                2, 50, Duration.ofMinutes(1), 1));
        var strict = new StrictRedisLimiter(() -> {
            throw new RedisConnectionException("down");
        }, new Semaphore(1), breaker, onFailure);
        Rule rule = rule(FailureMode.LOCAL_FALLBACK, Algorithm.TOKEN_BUCKET);

        for (int i = 0; i < 10; i++) {
            assertThat(strict.acquire(rule, "alice", 1)).isSameAs(FROM_LOCAL_SHARE);
        }

        assertThat(breaker.isClosed()).isFalse();
        assertThat(breaker.retryAfter()).isEqualTo(Duration.ofMinutes(1));
    }
}
