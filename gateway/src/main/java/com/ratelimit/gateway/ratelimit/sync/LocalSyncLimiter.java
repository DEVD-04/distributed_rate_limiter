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
import java.util.concurrent.Executor;
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
 * <p><b>Batches follow demand.</b> Each (rule, key) keeps an estimate of the tokens it will be asked for
 * over the next horizon: every request adds its cost, and the total fades exponentially with that
 * horizon. A call asks for about that much. When little of the latest batch is left, and more requests
 * are expected, the next batch is fetched in the background, so a busy key rarely waits.
 *
 * <p><b>Borrowed tokens expire.</b> Twice the horizon after the latest grant, what is left may no longer
 * be spent. It is given back with the next call for that (rule, key); if there never is one, it is lost
 * until the shared bucket refills, which only means admitting less. Tokens being returned are taken out
 * of the state before the call, so whatever happens to the call they are not sent again.
 *
 * <p>When a node does not hold enough for a request, the request waits for a lease call. <b>There is at
 * most one call in progress per (rule, key):</b> requests arriving meanwhile wait for the same one, and
 * the next call asks for enough to cover those still waiting. The request that made the call is served
 * from the grant first.
 *
 * <p><b>What the shared bucket cannot give, it is not asked for again at once.</b> When a reply says it
 * cannot cover another request yet, requests that cannot be served locally are refused from memory,
 * so a flood of refused requests does not become a flood of Redis calls. The bucket is asked again when
 * that wait is over, or after a horizon if sooner, because other nodes may give tokens back; and at once
 * if this node itself has expired tokens to give back.
 *
 * <p>If a lease call fails, the requests that were waiting for it go to the fallback limiter. Tokens
 * already borrowed are still spent until they expire.
 *
 * <p><b>When a rule's parameters change</b>, what was borrowed under the old ones is thrown away: not spent
 * and <em>not given back</em>, and so is a grant that arrives for a call made under them. Giving them back
 * would be wrong. The shared bucket is clamped to the new capacity once, but each node returns at its own
 * next request, and every such return would fill the bucket up to the new capacity again after some of it
 * had been spent. Throwing tokens away only admits less. "No tokens" is forgotten too, so a raised limit
 * applies at once. While gateways switch rules, requests still holding the old rule can make a key change
 * back and forth for a moment.
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

        TokenBucketParams params; // the rule parameters the fields below belong to
        long tokens;         // borrowed, unspent and not expired
        long expiresAtNanos;  // of those tokens
        long toReturn;        // expired and not yet given back
        long lastBatch;       // size of the latest grant

        double demand;        // tokens expected over the next horizon
        long demandAtNanos;

        long waiting;         // tokens wanted by requests waiting for the call in progress
        CompletableFuture<Boolean> call; // non-null while borrowing; completes with false if the call failed
        boolean lastCallFailed;
        long failedAtNanos;

        // from the latest reply
        long sharedRemaining;
        long fullAtNanos;
        boolean sharedShort;   // the shared bucket could not cover another request
        long availableAtNanos; // while short: when it expects to be able to
        long askAgainAtNanos;  // while short: not asked before this
    }

    /** A lease call that has been announced in {@link State#call} and is still to be made. */
    private record PendingCall(CompletableFuture<Boolean> done, long want, long need, long returned) {
    }

    /**
     * @param answered whether the shared bucket replied
     * @param served   the decision for the request that made the call, if the grant covered it
     */
    private record Reply(boolean answered, Decision served) {
    }

    private final LeaseSource source;
    private final IntSupplier nodeCount;
    private final RateLimiter fallback;
    private final LeaseSettings settings;
    private final long horizonNanos;
    private final Executor background;
    private final LongSupplier nanoClock;
    private final Cache<BucketKey, State> states;
    private final AtomicLong lastFailureLogNanos;

    /**
     * @param source     the shared buckets
     * @param nodeCount  how many nodes are sharing them right now
     * @param fallback   decides requests whose lease call failed
     * @param maxKeys    how many (rule, key) states to hold; a forgotten one only loses borrowed tokens
     * @param background runs the refills nobody is waiting for; it must not run them on a shared small pool,
     *                   since each one blocks for a Redis call
     * @param nanoClock  monotonic nanosecond clock; tests pass a fake one
     */
    public LocalSyncLimiter(LeaseSource source, IntSupplier nodeCount, RateLimiter fallback, LeaseSettings settings,
            long maxKeys, Executor background, LongSupplier nanoClock) {
        this.source = source;
        this.nodeCount = nodeCount;
        this.fallback = fallback;
        this.settings = settings;
        this.horizonNanos = settings.horizon().toNanos();
        this.background = background;
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
        boolean counted = false;
        while (true) {
            PendingCall mine = null;
            CompletableFuture<Boolean> others = null;
            Decision fromMemory = null;
            PendingCall refill = null;
            state.lock.lock();
            try {
                long now = nanoClock.getAsLong();
                if (!counted) {
                    addDemand(state, units, now);
                    counted = true;
                }
                state.waiting -= queued; // in the same step as retrying, so the next caller counts only the unserved
                queued = 0;
                adopt(state, params);
                expire(state, now);
                if (state.tokens >= units) {
                    state.tokens -= units;
                    fromMemory = allowed(params, state, now);
                    refill = planRefill(params, state, units, now);
                } else if ((state.sharedShort && now - state.askAgainAtNanos < 0 && state.toReturn == 0)
                        || calls == MAX_CALLS_PER_REQUEST) {
                    fromMemory = rejected(params, state, now);
                } else {
                    calls++;
                    if (state.call == null) {
                        mine = openCall(params, state, units, units - state.tokens);
                    } else {
                        others = state.call;
                        state.waiting += units;
                        queued = units;
                    }
                }
            } finally {
                state.lock.unlock();
            }

            if (fromMemory != null) {
                if (refill != null) {
                    startRefill(rule, key, params, state, refill);
                }
                return fromMemory;
            }
            if (others != null) {
                if (others.join()) { // always completed by whoever makes the call, whatever happens to it
                    continue;
                }
                forgetWaiting(state, queued);
                return fallback.acquire(rule, key, cost);
            }
            Reply reply = borrow(rule, key, params, state, mine, units);
            if (!reply.answered()) {
                return fallback.acquire(rule, key, cost);
            }
            if (reply.served() != null) {
                return reply.served();
            }
        }
    }

    /**
     * Announces a call. The lock is held.
     *
     * @param units what the request making the call costs, 0 for a refill
     * @param need  what that request is short of
     */
    private PendingCall openCall(TokenBucketParams params, State state, long units, long need) {
        long unmet = units + state.waiting - state.tokens;
        long expected = Math.max(settings.minBatch(), Math.round(state.demand));
        var call = new PendingCall(new CompletableFuture<>(), Math.min(params.capacity(), Math.max(unmet, expected)),
                need, state.toReturn);
        state.toReturn = 0; // taken out before the call: whatever happens to it, they are never sent again
        state.call = call.done();
        return call;
    }

    /** After a request was served: the refill to start now, or null. The lock is held. */
    private PendingCall planRefill(TokenBucketParams params, State state, long units, long now) {
        boolean worthIt = state.call == null
                && state.tokens < settings.refillBelow() * state.lastBatch
                // with no more than this one request expected, new tokens would only expire unused
                && state.demand >= 2.0 * units
                && !(state.sharedShort && now - state.askAgainAtNanos < 0)
                && !(state.lastCallFailed && now - state.failedAtNanos < horizonNanos);
        return worthIt ? openCall(params, state, 0, 0) : null;
    }

    private void startRefill(Rule rule, String key, TokenBucketParams params, State state, PendingCall call) {
        try {
            background.execute(() -> borrow(rule, key, params, state, call, 0));
        } catch (RuntimeException e) { // refused, e.g. while shutting down: the call was never made
            logFailure(rule, e);
            state.lock.lock();
            try {
                state.toReturn += call.returned(); // never sent, so still ours to send
                state.call = null;
                state.lastCallFailed = true;
                state.failedAtNanos = nanoClock.getAsLong();
            } finally {
                state.lock.unlock();
            }
            call.done().complete(false);
        }
    }

    /**
     * Makes an announced call, applies the reply and releases the requests waiting for it.
     *
     * @param units what the calling request costs, so it is served before anyone waiting; 0 for a refill
     */
    private Reply borrow(Rule rule, String key, TokenBucketParams params, State state, PendingCall call, long units) {
        Lease lease = null;
        Decision served = null;
        PendingCall refill = null;
        try {
            lease = source.lease(rule.ruleId(), key, params, call.want(), call.need(), call.returned(),
                    nodeCount.getAsInt());
        } catch (RuntimeException e) {
            logFailure(rule, e);
        } finally {
            state.lock.lock();
            try {
                long now = nanoClock.getAsLong();
                state.call = null;
                state.lastCallFailed = lease == null;
                if (lease == null) {
                    state.failedAtNanos = now;
                } else if (params.equals(state.params)) { // else the rule changed meanwhile: the grant is dropped
                    record(state, lease, now);
                    if (units > 0 && state.tokens >= units) {
                        state.tokens -= units;
                        served = allowed(params, state, now);
                        refill = planRefill(params, state, units, now);
                    }
                }
            } finally {
                state.lock.unlock();
            }
            call.done().complete(lease != null);
        }
        if (refill != null) {
            startRefill(rule, key, params, state, refill);
        }
        return new Reply(lease != null, served);
    }

    private void record(State state, Lease lease, long now) {
        expire(state, now); // first, so that a new grant cannot give expired tokens a new life
        if (lease.granted() > 0) {
            state.tokens += lease.granted();
            state.lastBatch = lease.granted();
            state.expiresAtNanos = now + 2 * horizonNanos;
        }
        state.sharedRemaining = lease.sharedRemaining();
        state.fullAtNanos = now + millisToNanos(lease.fullMs());
        state.sharedShort = lease.waitMs() > 0;
        if (state.sharedShort) {
            long wait = millisToNanos(lease.waitMs());
            state.availableAtNanos = now + wait;
            state.askAgainAtNanos = now + Math.min(wait, horizonNanos);
        }
    }

    /** Makes the state belong to {@code params}, dropping whatever came from different ones. The lock is held. */
    private static void adopt(State state, TokenBucketParams params) {
        if (params.equals(state.params)) {
            return;
        }
        state.params = params; // on a new state, the rest is already empty
        state.tokens = 0;
        state.toReturn = 0; // see the class comment: borrowed under other parameters, so never returned either
        state.lastBatch = 0;
        state.sharedShort = false;
    }

    private static void expire(State state, long now) {
        if (state.tokens > 0 && now - state.expiresAtNanos >= 0) {
            state.toReturn += state.tokens;
            state.tokens = 0;
        }
    }

    private void addDemand(State state, long units, long now) {
        double faded = state.demand == 0
                ? 0
                : state.demand * Math.exp(-(double) Math.max(0, now - state.demandAtNanos) / horizonNanos);
        state.demand = faded + units;
        state.demandAtNanos = now;
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
