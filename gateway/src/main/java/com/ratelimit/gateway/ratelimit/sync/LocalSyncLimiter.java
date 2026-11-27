package com.ratelimit.gateway.ratelimit.sync;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.sync.LeaseSource.Lease;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.TokenBucketParams;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code LOCAL_SYNC} token bucket: requests spend tokens this node has borrowed from the shared bucket,
 * so most are decided from memory. A node never refills tokens itself; it only spends what it was
 * granted, which has already been taken out of the shared bucket.
 *
 * <p>When a node does not hold enough for a request, the request waits for a lease call. <b>There is at
 * most one call in progress per (rule, key):</b> requests arriving meanwhile wait for the same one, and
 * the next call asks for enough to cover those still waiting. The request that made the call is served
 * from the grant first.
 *
 * <p><b>What the shared bucket cannot give, it is not asked for again at once.</b> When a reply says it
 * cannot cover another request yet, requests that cannot be served locally are refused from memory,
 * so a flood of refused requests does not become a flood of Redis calls. The bucket is asked again when
 * that wait is over, or after the recheck interval if sooner, because other nodes may give tokens back.
 *
 * <p>If a lease call fails, the requests that were waiting for it go to the fallback limiter. Tokens
 * already borrowed are still spent first.
 *
 * <p>Each (rule, key) has its own lock, held only for a few field updates and never during a call. A
 * {@link ReentrantLock} and futures are used, so a waiting virtual thread is never pinned.
 */
public class LocalSyncLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LocalSyncLimiter.class);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Duration IDLE_BEFORE_FORGOTTEN = Duration.ofMinutes(10);
    private static final long MAX_REMEMBERED_MS = TimeUnit.DAYS.toMillis(30);

    /**
     * How many lease calls one request will wait for. Each successful call serves at least the request
     * that made it, so a request only gets here by repeatedly losing to others for the new tokens.
     */
    static final int MAX_CALLS_PER_REQUEST = 10;

    private record BucketKey(long ruleId, String subject) {
    }

    /** What this node knows about one (rule, key). Every field is guarded by {@link #lock}. */
    private static final class State {
        final ReentrantLock lock = new ReentrantLock();
        long tokens;    // borrowed and not yet spent
        long waiting;   // tokens wanted by requests waiting for the call in progress
        CompletableFuture<Boolean> call; // non-null while borrowing; completes with false if the call failed
        // from the latest reply
        long sharedRemaining;
        long fullAtNanos;
        boolean sharedShort;   // the shared bucket could not cover another request
        long availableAtNanos; // while short: when it expects to be able to
        long askAgainAtNanos;  // while short: not asked before this
    }

    private final LeaseSource source;
    private final IntSupplier nodeCount;
    private final RateLimiter fallback;
    private final long batchSize;
    private final long recheckNanos;
    private final LongSupplier nanoClock;
    private final Cache<BucketKey, State> states;
    private final AtomicLong lastFailureLogNanos;

    /**
     * @param source    the shared buckets
     * @param nodeCount how many nodes are sharing them right now
     * @param fallback  decides requests whose lease call failed
     * @param batchSize tokens a call asks for, unless waiting requests need more
     * @param recheck   the longest a "no tokens" reply is believed before asking again
     * @param maxKeys   how many (rule, key) states to hold; a forgotten one only loses borrowed tokens
     * @param nanoClock monotonic nanosecond clock; tests pass a fake one
     */
    public LocalSyncLimiter(LeaseSource source, IntSupplier nodeCount, RateLimiter fallback, long batchSize,
            Duration recheck, long maxKeys, LongSupplier nanoClock) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be at least 1, was " + batchSize);
        }
        if (recheck.toNanos() < 1) {
            throw new IllegalArgumentException("recheck interval must be positive, was " + recheck);
        }
        this.source = source;
        this.nodeCount = nodeCount;
        this.fallback = fallback;
        this.batchSize = batchSize;
        this.recheckNanos = recheck.toNanos();
        this.nanoClock = nanoClock;
        this.states = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterAccess(IDLE_BEFORE_FORGOTTEN)
                .ticker(nanoClock::getAsLong)
                .build();
        this.lastFailureLogNanos = new AtomicLong(nanoClock.getAsLong() - LOG_INTERVAL_NANOS);
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        if (rule.algorithm() != Algorithm.TOKEN_BUCKET) {
            throw new UnsupportedOperationException("LOCAL_SYNC token leasing does not support " + rule.algorithm());
        }
        if (cost < 1) {
            throw new IllegalArgumentException("cost must be at least 1, was " + cost);
        }
        TokenBucketParams params = (TokenBucketParams) rule.params();
        long units = (long) params.cost() * cost;
        if (units > params.capacity()) {
            throw new IllegalArgumentException(
                    "request cost " + units + " exceeds capacity " + params.capacity() + " of rule " + rule.name());
        }

        State state = states.get(new BucketKey(rule.ruleId(), key), k -> new State());
        int calls = 0;
        long queued = 0; // what this request has added to state.waiting
        while (true) {
            CompletableFuture<Boolean> mine = null;
            CompletableFuture<Boolean> others = null;
            long want = 0;
            long need = 0;
            state.lock.lock();
            try {
                state.waiting -= queued; // in the same step as retrying, so the next caller counts only the unserved
                queued = 0;
                long now = nanoClock.getAsLong();
                if (state.tokens >= units) {
                    state.tokens -= units;
                    return allowed(params, state, now);
                }
                if ((state.sharedShort && now - state.askAgainAtNanos < 0) || calls == MAX_CALLS_PER_REQUEST) {
                    return rejected(params, state, now);
                }
                calls++;
                if (state.call == null) {
                    mine = new CompletableFuture<>();
                    state.call = mine;
                    need = units - state.tokens;
                    want = Math.min(params.capacity(), Math.max(batchSize, units + state.waiting - state.tokens));
                } else {
                    others = state.call;
                    state.waiting += units;
                    queued = units;
                }
            } finally {
                state.lock.unlock();
            }

            if (others != null) {
                if (others.join()) { // always completed by the caller, whatever happens to its call
                    continue;
                }
                forgetWaiting(state, queued);
                return fallback.acquire(rule, key, cost);
            }

            Lease lease = null;
            Decision served = null;
            try {
                lease = source.lease(rule.ruleId(), key, params, want, need, 0, nodeCount.getAsInt());
            } catch (RuntimeException e) {
                logFailure(rule, e);
            } finally {
                state.lock.lock();
                try {
                    if (lease != null) {
                        long now = nanoClock.getAsLong();
                        record(state, lease, now);
                        if (state.tokens >= units) { // the caller is served before anyone waiting sees the grant
                            state.tokens -= units;
                            served = allowed(params, state, now);
                        }
                    }
                    state.call = null;
                } finally {
                    state.lock.unlock();
                }
                mine.complete(lease != null);
            }
            if (lease == null) {
                return fallback.acquire(rule, key, cost);
            }
            if (served != null) {
                return served;
            }
        }
    }

    private void record(State state, Lease lease, long now) {
        state.tokens += lease.granted();
        state.sharedRemaining = lease.sharedRemaining();
        state.fullAtNanos = now + millisToNanos(lease.fullMs());
        state.sharedShort = lease.waitMs() > 0;
        if (state.sharedShort) {
            long wait = millisToNanos(lease.waitMs());
            state.availableAtNanos = now + wait;
            state.askAgainAtNanos = now + Math.min(wait, recheckNanos);
        }
    }

    private static void forgetWaiting(State state, long units) {
        state.lock.lock();
        try {
            state.waiting -= units;
        } finally {
            state.lock.unlock();
        }
    }

    /** "Remaining" is what this node holds plus what the shared bucket held at the latest reply: an estimate. */
    private static Decision allowed(TokenBucketParams params, State state, long now) {
        return Decision.allowed(params.capacity(), remaining(params, state), resetMs(state, now));
    }

    private static Decision rejected(TokenBucketParams params, State state, long now) {
        long retryAfterMs = state.sharedShort ? Math.max(1, nanosToMillisCeil(state.availableAtNanos - now)) : 1;
        return Decision.rejected(params.capacity(), remaining(params, state), retryAfterMs, resetMs(state, now));
    }

    private static long remaining(TokenBucketParams params, State state) {
        return Math.min(params.capacity(), state.tokens + state.sharedRemaining);
    }

    private static long resetMs(State state, long now) {
        return Math.max(0, nanosToMillisCeil(state.fullAtNanos - now));
    }

    private static long millisToNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(Math.min(millis, MAX_REMEMBERED_MS)); // capped: no overflow later
    }

    private static long nanosToMillisCeil(long nanos) {
        return Math.ceilDiv(nanos, 1_000_000L);
    }

    private void logFailure(Rule rule, RuntimeException e) {
        long now = nanoClock.getAsLong();
        long last = lastFailureLogNanos.get();
        if (now - last >= LOG_INTERVAL_NANOS && lastFailureLogNanos.compareAndSet(last, now)) {
            log.error("Cannot borrow tokens, limiting on this node alone (rule '{}'): {}", rule.name(), e.toString());
        }
    }
}
