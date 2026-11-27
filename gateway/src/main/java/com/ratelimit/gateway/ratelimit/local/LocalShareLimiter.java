package com.ratelimit.gateway.ratelimit.local;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.rules.LeakyBucketParams;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.SlidingWindowParams;
import com.ratelimit.rules.TokenBucketParams;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * This node's share of every limit, enforced from its own memory: the {@code LOCAL_FALLBACK} behaviour
 * for when Redis cannot be used. With N nodes each gets 1/N, so together they allow about the limit,
 * provided the traffic is spread evenly. A node that gets most of it is still held to its share.
 *
 * <ul>
 *   <li><b>Token bucket:</b> capacity / N and refill / N. The capacity is never less than one request
 *       costs, or nothing could pass; with a limit smaller than N the nodes together allow up to N.</li>
 *   <li><b>Sliding window:</b> limit / N, at least 1, over the same window.</li>
 *   <li><b>Leaky bucket:</b> each node releases at drain rate / N. The longest wait stays the rule's, since
 *       proxy timeouts are set with it in mind, so a node's queue holds about 1/N as many requests.</li>
 * </ul>
 *
 * <p>N is read on every call, as are the rule's parameters, so a change in either applies to what a key
 * has already used. A key's local state starts unused and is never reset: it recovers only with time,
 * like any limit, so Redis failing again and again does not hand out a new allowance each time. It is
 * dropped after being idle long enough to have fully recovered, or earlier if more than {@code maxKeys}
 * keys are held, which bounds memory at the price of a fresh allowance for a dropped key.
 */
public class LocalShareLimiter implements RateLimiter {

    private static final long IDLE_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** Holds the calling thread for a leaky bucket's wait. */
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private record StateKey(long ruleId, String key) {
    }

    /** One key's state and how long it may sit unused before dropping it loses nothing. */
    private static final class Held<S> {
        final S state;
        volatile long idleTtlNanos;

        Held(S state, long idleTtlNanos) {
            this.state = state;
            this.idleTtlNanos = idleTtlNanos;
        }
    }

    private final IntSupplier nodeCount;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final Semaphore waiting;
    private final InMemoryRateLimiter tokenBuckets;
    private final Cache<StateKey, Held<LocalSlidingWindow>> windows;
    private final Cache<StateKey, Held<LocalLeakyBucket>> queues;

    /**
     * @param nodeCount  how many nodes are sharing the limits, as last known
     * @param maxKeys    how many keys to hold per algorithm
     * @param maxWaiting how many leaky-bucket requests this node may hold at once; more are turned away
     */
    public LocalShareLimiter(IntSupplier nodeCount, long maxKeys, int maxWaiting) {
        this(nodeCount, maxKeys, new Semaphore(maxWaiting));
    }

    /** @param waiting as above, but a bound shared with whatever else holds leaky-bucket requests on this node */
    public LocalShareLimiter(IntSupplier nodeCount, long maxKeys, Semaphore waiting) {
        this(nodeCount, maxKeys, waiting, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    LocalShareLimiter(IntSupplier nodeCount, long maxKeys, int maxWaiting, LongSupplier nanoClock, Sleeper sleeper) {
        this(nodeCount, maxKeys, new Semaphore(maxWaiting), nanoClock, sleeper);
    }

    private LocalShareLimiter(IntSupplier nodeCount, long maxKeys, Semaphore waiting, LongSupplier nanoClock,
            Sleeper sleeper) {
        this.nodeCount = nodeCount;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.waiting = waiting;
        this.tokenBuckets = new InMemoryRateLimiter(nanoClock, maxKeys);
        this.windows = cache(maxKeys, nanoClock);
        this.queues = cache(maxKeys, nanoClock);
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        if (cost < 1) {
            throw new IllegalArgumentException("cost must be at least 1, was " + cost);
        }
        int nodes = Math.max(1, nodeCount.getAsInt());
        return switch (rule.algorithm()) {
            case TOKEN_BUCKET -> tokenBucket(rule, key, cost, nodes);
            case SLIDING_WINDOW_LOG -> slidingWindow(rule, key, cost, nodes);
            case LEAKY_BUCKET_QUEUE -> leakyBucket(rule, key, cost, nodes);
        };
    }

    private Decision tokenBucket(Rule rule, String key, int cost, int nodes) {
        TokenBucketParams params = (TokenBucketParams) rule.params();
        long units = (long) params.cost() * cost;
        if (units > params.capacity()) {
            throw new IllegalArgumentException(
                    "request cost " + units + " exceeds capacity " + params.capacity() + " of rule " + rule.name());
        }
        var share = new TokenBucketParams(
                Math.max(units, params.capacity() / nodes),
                Math.max(1.0 / TokenBucketParams.MICRO, params.refillPerSec() / nodes), // the smallest rate there is
                params.cost());
        return tokenBuckets.acquire(withParams(rule, share), key, cost);
    }

    private Decision slidingWindow(Rule rule, String key, int cost, int nodes) {
        SlidingWindowParams params = (SlidingWindowParams) rule.params();
        requireSingle(cost, "a sliding window counts requests");
        long limit = Math.max(1, params.limit() / nodes);
        long windowNanos = TimeUnit.MILLISECONDS.toNanos(params.windowMs());

        LocalSlidingWindow window = state(windows, new StateKey(rule.ruleId(), key), LocalSlidingWindow::new,
                windowNanos + IDLE_MARGIN_NANOS);
        LocalSlidingWindow.Outcome outcome = window.tryAcquire(limit, windowNanos, nanoClock);

        if (outcome.allowed()) {
            return Decision.allowed(limit, outcome.remaining(), millisUp(outcome.resetNanos()));
        }
        return Decision.rejected(limit, outcome.remaining(), Math.max(1, millisUp(outcome.retryAfterNanos())),
                millisUp(outcome.resetNanos()));
    }

    private Decision leakyBucket(Rule rule, String key, int cost, int nodes) {
        LeakyBucketParams params = (LeakyBucketParams) rule.params();
        requireSingle(cost, "a leaky bucket releases requests one by one");
        long intervalNanos = Math.round(params.intervalMs() * nodes * 1_000_000);
        long maxWaitNanos = Math.round(params.maxWaitMs() * 1_000_000);
        long queueShare = params.queueCapacity() / nodes; // may be 0: then a request passes only when a slot is due
        long shownLimit = Math.max(1, queueShare);

        // taken before a slot is reserved, so a node already holding too many does not reserve one it will not use
        if (!waiting.tryAcquire()) {
            return Decision.queueFull(shownLimit, Math.max(1, millisUp(intervalNanos)), 0);
        }
        try {
            LocalLeakyBucket queue = state(queues, new StateKey(rule.ruleId(), key), LocalLeakyBucket::new,
                    maxWaitNanos + intervalNanos + IDLE_MARGIN_NANOS);
            LocalLeakyBucket.Outcome outcome = queue.tryAcquire(intervalNanos, maxWaitNanos, nanoClock);
            long resetMs = millisUp(outcome.resetNanos());
            if (!outcome.allowed()) {
                return Decision.queueFull(shownLimit, Math.max(1, millisUp(outcome.retryAfterNanos())), resetMs);
            }
            if (outcome.waitNanos() > 0) {
                try {
                    sleeper.sleep(outcome.waitNanos());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // the caller is going away; the slot simply goes unused
                    return Decision.queueFull(shownLimit, Math.max(1, millisUp(intervalNanos)), resetMs);
                }
            }
            long ahead = Math.round((double) outcome.waitNanos() / intervalNanos);
            return Decision.allowedAfterWait(shownLimit, Math.max(0, queueShare - ahead), resetMs,
                    millisUp(outcome.waitNanos()));
        } finally {
            waiting.release();
        }
    }

    private static void requireSingle(int cost, String because) {
        if (cost != 1) {
            throw new IllegalArgumentException(because + ", so cost must be 1, was " + cost);
        }
    }

    private static Rule withParams(Rule rule, TokenBucketParams params) {
        return new Rule(rule.ruleId(), rule.name(), rule.limitBy(), rule.matchTier(), rule.matchSubject(),
                rule.endpointPattern(), rule.httpMethod(), rule.algorithm(), rule.mode(), rule.failureMode(), params,
                rule.priority(), rule.version());
    }

    private static <S> S state(Cache<StateKey, Held<S>> cache, StateKey key, java.util.function.Supplier<S> fresh,
            long idleTtlNanos) {
        Held<S> held = cache.get(key, k -> new Held<>(fresh.get(), idleTtlNanos));
        if (held.idleTtlNanos != idleTtlNanos) {
            // the rule or the node count changed: how long idle state must be kept changed with it
            held.idleTtlNanos = idleTtlNanos;
            cache.policy().expireVariably()
                    .ifPresent(p -> p.setExpiresAfter(key, idleTtlNanos, TimeUnit.NANOSECONDS));
        }
        return held.state;
    }

    private static <S> Cache<StateKey, Held<S>> cache(long maxKeys, LongSupplier nanoClock) {
        return Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .ticker(nanoClock::getAsLong)
                .expireAfter(new Expiry<StateKey, Held<S>>() {
                    @Override
                    public long expireAfterCreate(StateKey key, Held<S> held, long currentTime) {
                        return held.idleTtlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(StateKey key, Held<S> held, long currentTime, long currentDuration) {
                        return held.idleTtlNanos;
                    }

                    @Override
                    public long expireAfterRead(StateKey key, Held<S> held, long currentTime, long currentDuration) {
                        return held.idleTtlNanos;
                    }
                })
                .build();
    }

    private static long millisUp(long nanos) {
        return Math.ceilDiv(nanos, 1_000_000L);
    }
}
