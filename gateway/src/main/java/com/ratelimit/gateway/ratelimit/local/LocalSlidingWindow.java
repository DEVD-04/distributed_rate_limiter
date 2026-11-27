package com.ratelimit.gateway.ratelimit.local;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Sliding window log for one (rule, key) in this node's memory: the times of the admitted requests still
 * inside the window. It counts the way the Redis script does: a request counts for exactly one window,
 * and refused requests are not recorded.
 *
 * <p>The clock is read while holding the lock, so the times are always added in order.
 */
final class LocalSlidingWindow {

    /** @param remaining requests that may still be made now */
    record Outcome(boolean allowed, long remaining, long retryAfterNanos, long resetNanos) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Long> admitted = new ArrayDeque<>(); // oldest first

    /** @param limit and {@code windowNanos} are supplied on every call, so a change applies at once */
    Outcome tryAcquire(long limit, long windowNanos, LongSupplier nanoClock) {
        lock.lock();
        try {
            long now = nanoClock.getAsLong();
            while (!admitted.isEmpty() && now - admitted.peekFirst() >= windowNanos) {
                admitted.pollFirst();
            }
            boolean allowed = admitted.size() < limit;
            long retryAfter = 0;
            if (allowed) {
                admitted.addLast(now);
            } else {
                // there is room once fewer than `limit` are left. Usually that is when the oldest leaves; more
                // must leave if the limit has shrunk below what is already recorded.
                Iterator<Long> oldestFirst = admitted.iterator();
                long frees = oldestFirst.next();
                for (long i = admitted.size() - limit; i > 0; i--) {
                    frees = oldestFirst.next();
                }
                retryAfter = frees + windowNanos - now;
            }
            long reset = admitted.peekLast() + windowNanos - now;
            return new Outcome(allowed, Math.max(0, limit - admitted.size()), retryAfter, reset);
        } finally {
            lock.unlock();
        }
    }
}
