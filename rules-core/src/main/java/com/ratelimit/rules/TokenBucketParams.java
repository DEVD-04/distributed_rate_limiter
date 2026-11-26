package com.ratelimit.rules;

/**
 * Token bucket settings.
 *
 * @param capacity     maximum tokens, i.e. the largest burst
 * @param refillPerSec tokens added per second (may be fractional, e.g. 0.5 = one every 2 s)
 * @param cost         tokens one request spends
 */
public record TokenBucketParams(long capacity, double refillPerSec, int cost) implements Params {

    /** Token amounts are tracked in millionths of a token so fractional refills stay exact. */
    public static final long MICRO = 1_000_000L;

    /** Keeps {@code capacity * MICRO} (and products with nanoseconds) comfortably inside a long. */
    public static final long MAX_CAPACITY = 1_000_000_000L;

    public TokenBucketParams {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be in [1, " + MAX_CAPACITY + "], was " + capacity);
        }
        if (!(refillPerSec >= 1.0 / MICRO) || refillPerSec > MAX_CAPACITY) {
            throw new IllegalArgumentException("refill_per_sec must be in [0.000001, " + MAX_CAPACITY
                    + "], was " + refillPerSec);
        }
        if (cost < 1 || cost > capacity) {
            throw new IllegalArgumentException("cost must be in [1, capacity], was " + cost);
        }
    }

    public TokenBucketParams(long capacity, double refillPerSec) {
        this(capacity, refillPerSec, 1);
    }

    public long capacityMicro() {
        return capacity * MICRO;
    }

    /** Refill speed in micro-tokens per second (always at least 1). */
    public long refillMicroPerSecond() {
        return Math.max(1L, Math.round(refillPerSec * MICRO));
    }
}
