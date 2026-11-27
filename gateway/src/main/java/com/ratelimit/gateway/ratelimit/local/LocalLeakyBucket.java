package com.ratelimit.gateway.ratelimit.local;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Leaky bucket (queue variant) for one (rule, key) in this node's memory, by the same virtual scheduling
 * as the Redis script: there is no queue, only the time at which the next request may be released. A
 * request reserves that slot and is told how long to wait for it. The lock is never held while waiting.
 */
final class LocalLeakyBucket {

    /** Absorbs the rounding of fractional intervals to whole nanoseconds. */
    private static final long TOLERANCE_NANOS = 1_000;

    /**
     * @param waitNanos       if allowed, how long until the reserved slot
     * @param retryAfterNanos if refused, how long until a slot within the longest wait exists
     * @param resetNanos      how long until the queue is empty
     */
    record Outcome(boolean allowed, long waitNanos, long retryAfterNanos, long resetNanos) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private boolean primed;
    private long nextFreeNanos;

    Outcome tryAcquire(long intervalNanos, long maxWaitNanos, LongSupplier nanoClock) {
        lock.lock();
        try {
            long now = nanoClock.getAsLong();
            long wait = primed ? Math.max(0, nextFreeNanos - now) : 0;
            if (wait > maxWaitNanos + TOLERANCE_NANOS) {
                return new Outcome(false, 0, wait - maxWaitNanos, wait); // nothing is reserved
            }
            primed = true;
            nextFreeNanos = now + wait + intervalNanos;
            return new Outcome(true, wait, 0, wait + intervalNanos);
        } finally {
            lock.unlock();
        }
    }
}
