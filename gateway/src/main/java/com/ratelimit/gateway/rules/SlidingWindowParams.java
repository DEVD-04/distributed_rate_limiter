package com.ratelimit.gateway.rules;

/**
 * Sliding window log settings: at most {@code limit} requests in any window of {@code windowMs}.
 * Redis keeps one entry per request in the window, so memory grows with {@code limit}; the limit is
 * capped to keep a single key bounded.
 *
 * @param limit    requests allowed per window
 * @param windowMs length of the rolling window, in milliseconds
 */
public record SlidingWindowParams(long limit, long windowMs) implements Params {

    public static final long MAX_LIMIT = 100_000;
    public static final long MAX_WINDOW_MS = 24L * 60 * 60 * 1000;

    public SlidingWindowParams {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in [1, " + MAX_LIMIT + "], was " + limit);
        }
        if (windowMs < 1 || windowMs > MAX_WINDOW_MS) {
            throw new IllegalArgumentException("window_ms must be in [1, " + MAX_WINDOW_MS + "], was " + windowMs);
        }
    }
}
