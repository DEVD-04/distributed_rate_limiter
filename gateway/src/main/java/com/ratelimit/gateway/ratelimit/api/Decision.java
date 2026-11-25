package com.ratelimit.gateway.ratelimit.api;

/**
 * Outcome of one rate-limit check.
 *
 * @param allowed      whether the request may proceed
 * @param limit        the rule's capacity, for the {@code X-RateLimit-Limit} header
 * @param remaining    whole tokens left after this request
 * @param retryAfterMs when rejected: how long until a retry can succeed (0 when allowed)
 * @param resetMs      how long until the bucket is full again
 * @param waitedMs     how long the request was delayed by the limiter (leaky bucket queue only)
 * @param reason       why the decision came out this way
 */
public record Decision(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterMs,
        long resetMs,
        long waitedMs,
        Reason reason) {

    public static Decision allowed(long limit, long remaining, long resetMs) {
        return new Decision(true, limit, remaining, 0, resetMs, 0, Reason.ALLOWED);
    }

    /** The limiter could not decide (shared store down); the request is let through. */
    public static Decision failedOpen(long limit) {
        return new Decision(true, limit, limit, 0, 0, 0, Reason.LIMITER_UNAVAILABLE);
    }

    public static Decision rejected(long limit, long remaining, long retryAfterMs, long resetMs) {
        return new Decision(false, limit, remaining, retryAfterMs, resetMs, 0, Reason.LIMIT_EXCEEDED);
    }
}
