package com.ratelimit.gateway.rules;

/**
 * Leaky bucket (queue variant) settings. Requests are released at a constant rate of
 * {@code drainPerSec}; those that arrive faster wait their turn, up to {@code queueCapacity} requests
 * deep, and anything beyond that is rejected.
 *
 * <p>The longest a request can wait is {@code queueCapacity / drainPerSec}. It is derived, never
 * configured separately, so the two settings cannot contradict each other.
 *
 * @param drainPerSec   requests released per second (may be fractional)
 * @param queueCapacity how many requests may be waiting at once
 */
public record LeakyBucketParams(double drainPerSec, long queueCapacity) implements Params {

    public static final double MAX_DRAIN_PER_SEC = 100_000;
    public static final double MIN_DRAIN_PER_SEC = 0.001;
    public static final long MAX_QUEUE_CAPACITY = 1_000_000;
    /** A request held longer than this would outlive the timeouts of any sensible client or load balancer. */
    public static final long MAX_WAIT_MS = 60_000;

    public LeakyBucketParams {
        if (!(drainPerSec >= MIN_DRAIN_PER_SEC) || drainPerSec > MAX_DRAIN_PER_SEC) {
            throw new IllegalArgumentException("drain_per_sec must be in [" + MIN_DRAIN_PER_SEC + ", "
                    + MAX_DRAIN_PER_SEC + "], was " + drainPerSec);
        }
        if (queueCapacity < 1 || queueCapacity > MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queue_capacity must be in [1, " + MAX_QUEUE_CAPACITY + "], was " + queueCapacity);
        }
        if (queueCapacity * 1000.0 / drainPerSec > MAX_WAIT_MS) {
            throw new IllegalArgumentException("a full queue would hold requests for "
                    + Math.round(queueCapacity / drainPerSec) + " s; the limit is " + MAX_WAIT_MS / 1000
                    + " s (lower queue_capacity or raise drain_per_sec)");
        }
    }

    /** Time between two released requests, in milliseconds. */
    public double intervalMs() {
        return 1000.0 / drainPerSec;
    }

    /** The longest a request may wait: the time it takes to drain a full queue. */
    public double maxWaitMs() {
        return queueCapacity * intervalMs();
    }
}
