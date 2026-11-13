package com.ratelimit.gateway.ratelimit.local;

import static com.ratelimit.gateway.ratelimit.local.Fixed.NANOS_PER_SECOND;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.rules.Algorithm;
import com.ratelimit.gateway.rules.Rule;
import com.ratelimit.gateway.rules.TokenBucketParams;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Single-node token bucket limiter: all state lives in this JVM.
 *
 * <p>Buckets are held in a bounded cache. An idle bucket is dropped only once it would have
 * refilled completely (twice over, plus a second), so dropping it loses nothing: a new bucket
 * starts full anyway. The hard size bound exists to cap memory under a flood of distinct keys
 * (for example spoofed IPs); evicting a not-yet-full bucket that way can give that key a fresh
 * burst, which is the accepted price of bounded memory.
 *
 * <p>Idle expiry is handled by Caffeine's timer wheel, which is coarse: an expired bucket may stay
 * in memory (and in {@link #activeKeys()}) a while longer, but is never served once expired.
 */
public class InMemoryRateLimiter implements RateLimiter {

    /** Upper bound on the idle time, so a huge capacity / tiny rate cannot pin a bucket forever. */
    private static final long MAX_IDLE_TTL_NANOS = TimeUnit.DAYS.toNanos(30);
    private static final long TTL_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(1);

    private record BucketKey(long ruleId, String key) {
    }

    private final LongSupplier nanoClock;
    private final Cache<BucketKey, LocalTokenBucket> buckets;

    /** Production constructor: real monotonic clock. */
    public InMemoryRateLimiter(long maximumKeys) {
        this(System::nanoTime, maximumKeys);
    }

    /** @param nanoClock monotonic nanosecond clock; tests pass a fake one */
    public InMemoryRateLimiter(LongSupplier nanoClock, long maximumKeys) {
        this.nanoClock = nanoClock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maximumKeys)
                .ticker(nanoClock::getAsLong)
                .expireAfter(new Expiry<BucketKey, LocalTokenBucket>() {
                    @Override
                    public long expireAfterCreate(BucketKey key, LocalTokenBucket bucket, long currentTime) {
                        return bucket.idleTtlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(BucketKey key, LocalTokenBucket bucket, long currentTime,
                            long currentDuration) {
                        return bucket.idleTtlNanos();
                    }

                    @Override
                    public long expireAfterRead(BucketKey key, LocalTokenBucket bucket, long currentTime,
                            long currentDuration) {
                        return bucket.idleTtlNanos();
                    }
                })
                .build();
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        if (rule.algorithm() != Algorithm.TOKEN_BUCKET) {
            throw new UnsupportedOperationException("In-memory limiter does not support " + rule.algorithm());
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

        BucketKey bucketKey = new BucketKey(rule.ruleId(), key);
        LocalTokenBucket bucket = buckets.get(bucketKey, k -> new LocalTokenBucket(params, idleTtlNanos(params)));
        if (bucket.observe(params)) {
            // The rule was edited: make the idle lifetime, and the cache's own expiry, follow the new refill time.
            long ttl = idleTtlNanos(params);
            bucket.setIdleTtlNanos(ttl);
            buckets.policy().expireVariably().ifPresent(p -> p.setExpiresAfter(bucketKey, ttl, TimeUnit.NANOSECONDS));
        }

        LocalTokenBucket.Outcome outcome = bucket.tryAcquire(params, units * TokenBucketParams.MICRO, nanoClock);

        long remaining = outcome.remainingMicro() / TokenBucketParams.MICRO;
        long resetMs = nanosToMillisCeil(outcome.resetNanos());
        if (outcome.allowed()) {
            return Decision.allowed(params.capacity(), remaining, resetMs);
        }
        // A rejection always asks the client to wait at least 1 ms, never "0".
        long retryAfterMs = Math.max(1, nanosToMillisCeil(outcome.retryAfterNanos()));
        return Decision.rejected(params.capacity(), remaining, retryAfterMs, resetMs);
    }

    /** Number of buckets currently held (approximate; drives the active-keys metric later). */
    public long activeKeys() {
        return buckets.estimatedSize();
    }

    /** Runs pending maintenance (size eviction, expiry) now instead of lazily. Mainly for tests. */
    public void cleanUp() {
        buckets.cleanUp();
    }

    /** Time until the bucket for this rule and key would be dropped if left unused, if it exists. */
    OptionalLong idleTimeRemainingNanos(Rule rule, String key) {
        return buckets.policy().expireVariably()
                .map(p -> p.getExpiresAfter(new BucketKey(rule.ruleId(), key), TimeUnit.NANOSECONDS))
                .orElse(OptionalLong.empty());
    }

    /** How long an unused bucket is kept: twice its full-refill time, plus a margin, capped. */
    static long idleTtlNanos(TokenBucketParams params) {
        long fullRefill = Fixed.ceilMulDiv(params.capacityMicro(), NANOS_PER_SECOND, params.refillMicroPerSecond());
        long ttl = Fixed.saturatedAdd(Fixed.saturatedAdd(fullRefill, fullRefill), TTL_MARGIN_NANOS);
        return Math.min(ttl, MAX_IDLE_TTL_NANOS);
    }

    private static long nanosToMillisCeil(long nanos) {
        return Math.ceilDiv(nanos, 1_000_000L);
    }
}
