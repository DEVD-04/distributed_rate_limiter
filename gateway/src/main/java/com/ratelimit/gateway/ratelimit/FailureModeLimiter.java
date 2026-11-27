package com.ratelimit.gateway.ratelimit;

import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.rules.LeakyBucketParams;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.SlidingWindowParams;
import com.ratelimit.rules.TokenBucketParams;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides a request when Redis cannot be used, as the rule's {@code failure_mode} says. The Redis-backed
 * limiters hand a request here when their call failed, timed out or was skipped by the circuit breaker.
 *
 * <ul>
 *   <li>{@code FAIL_OPEN}: let it through. Nothing is counted or queued, so a leaky bucket releases
 *       everything at once.
 *   <li>{@code FAIL_CLOSED}: refuse it; the client gets a 503 and is told when to retry.
 *   <li>{@code LOCAL_FALLBACK}: limit it to this node's share, from memory.
 * </ul>
 */
public class FailureModeLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailureModeLimiter.class);

    private final RateLimiter localShare;
    private final Supplier<Duration> retryAfter;

    /**
     * @param localShare limits a request to this node's share, for {@code LOCAL_FALLBACK}
     * @param retryAfter what to tell a client refused by {@code FAIL_CLOSED} to wait
     */
    public FailureModeLimiter(RateLimiter localShare, Supplier<Duration> retryAfter) {
        this.localShare = localShare;
        this.retryAfter = retryAfter;
    }

    /** Lets everything through, whatever the rule says: for tests of the limiters that hand requests here. */
    public static RateLimiter alwaysOpen() {
        return (rule, key, cost) -> Decision.failedOpen(limitOf(rule));
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        Decision decision = switch (rule.failureMode()) {
            case FAIL_OPEN -> Decision.failedOpen(limitOf(rule));
            case FAIL_CLOSED -> Decision.failedClosed(limitOf(rule), Math.max(1, retryAfter.get().toMillis()));
            case LOCAL_FALLBACK -> localShare.acquire(rule, key, cost);
        };
        // off by default; one line per request when turned on, to see what happened during an outage
        log.debug("Without Redis, rule '{}' ({}): {}", rule.name(), rule.failureMode(), decision.reason());
        return decision;
    }

    private static long limitOf(Rule rule) {
        return switch (rule.params()) {
            case TokenBucketParams p -> p.capacity();
            case SlidingWindowParams p -> p.limit();
            case LeakyBucketParams p -> p.queueCapacity();
        };
    }
}
