package com.ratelimit.gateway.ratelimit.sync;

import com.ratelimit.rules.TokenBucketParams;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Stands in for Redis in tests of the node's side. It grants the way the lease script does (that script
 * has its own tests against a real Redis). It never refills by itself: a test adds tokens when it wants
 * time to have passed, and chooses the wait a "not enough" reply reports.
 */
class FakeSharedBucket implements LeaseSource {

    record Call(long ruleId, String subject, long want, long need, long returned, int nodeCount) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Long> tokens = new HashMap<>();
    private final long initial;

    volatile long waitMsWhenShort = 5_000;
    volatile long fullMs = 20_000;
    volatile RuntimeException failure;
    /** While set, every call is recorded and then held until the latch opens. */
    volatile CountDownLatch gate;

    FakeSharedBucket(long tokensPerBucket) {
        this.initial = tokensPerBucket;
    }

    synchronized void add(long ruleId, String subject, long amount) {
        tokens.put(ruleId + ":" + subject, left(ruleId, subject) + amount);
    }

    synchronized long left(long ruleId, String subject) {
        return tokens.getOrDefault(ruleId + ":" + subject, initial);
    }

    @Override
    public Lease lease(long ruleId, String subject, TokenBucketParams params,
            long want, long need, long returned, int nodeCount) {
        calls.add(new Call(ruleId, subject, want, need, returned, nodeCount));
        CountDownLatch held = gate;
        if (held != null) {
            try {
                held.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return grant(ruleId + ":" + subject, params, Math.max(want, need), need, returned, nodeCount);
    }

    private synchronized Lease grant(String key, TokenBucketParams params, long want, long need, long returned,
            int nodeCount) {
        long whole = Math.min(params.capacity(), tokens.getOrDefault(key, initial) + returned);
        long granted = whole < need ? 0
                : Math.max(need, Math.min(want, Math.min(Math.ceilDiv(whole, nodeCount), whole)));
        long remaining = whole - granted;
        tokens.put(key, remaining);
        return new Lease(granted, remaining, remaining < Math.max(need, 1) ? waitMsWhenShort : 0, fullMs);
    }
}
