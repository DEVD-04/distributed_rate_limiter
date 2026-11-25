package com.ratelimit.gateway.ratelimit.strict;

import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.redis.LuaScript;
import com.ratelimit.gateway.redis.RedisKeys;
import com.ratelimit.gateway.rules.Rule;
import com.ratelimit.gateway.rules.SlidingWindowParams;
import com.ratelimit.gateway.rules.TokenBucketParams;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exact, cluster-wide limiting: every decision is one atomic Lua script in Redis, so any number of
 * gateway nodes share the same counters and can never admit more than the limit allows.
 *
 * <p>All time in the scripts comes from Redis ({@code TIME}), never from a gateway clock, so nodes
 * with different clocks cannot disagree.
 *
 * <p>If Redis cannot be reached the request is let through and an error is logged (at most every ten
 * seconds, so an outage does not flood the log). That is a stop-gap: per-rule {@code failure_mode}
 * handling replaces it in the resilience phase.
 */
public class StrictRedisLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(StrictRedisLimiter.class);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long MAX_KEY_TTL_MS = TimeUnit.DAYS.toMillis(30);

    /**
     * Token bucket with lazy refill. Tokens are kept as a fractional number so no refill is ever
     * rounded away. KEYS[1] bucket hash; ARGV: capacity, refill_per_sec, cost, ttl_ms.
     * Returns {allowed, whole tokens left, retry_after_ms, ms until full}.
     */
    static final LuaScript TOKEN_BUCKET = new LuaScript("token_bucket", """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local cap, rate, cost, ttl = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens, ts = tonumber(d[1]), tonumber(d[2])
            if tokens == nil then tokens = cap; ts = now end
            tokens = math.min(cap, tokens + math.max(0, now - ts) * rate / 1000)
            local allowed, retry = 0, 0
            -- 1e-9 absorbs floating-point rounding, so a request is not refused at the very moment Retry-After promised
            if tokens + 1e-9 >= cost then tokens = math.max(0, tokens - cost); allowed = 1
            else retry = math.ceil((cost - tokens) * 1000 / rate) end
            -- never move the timestamp backwards, or the same elapsed time could be credited twice
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', math.max(now, ts))
            redis.call('PEXPIRE', KEYS[1], ttl)
            local reset = math.ceil((cap - tokens) * 1000 / rate)
            return {allowed, math.floor(tokens + 1e-9), retry, reset}
            """);

    /**
     * Sliding window log: a sorted set holding one entry (score = time in ms) per admitted request.
     * An entry counts for exactly window_ms: it is dropped once score <= now - window.
     * KEYS[1] the set; ARGV: window_ms, limit, unique member id.
     * Returns {allowed, requests left, retry_after_ms, ms until the window is empty}.
     */
    static final LuaScript SLIDING_WINDOW = new LuaScript("sliding_window", """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local window, limit = tonumber(ARGV[1]), tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            local count = redis.call('ZCARD', KEYS[1])
            local allowed, retry = 0, 0
            if count < limit then
              redis.call('ZADD', KEYS[1], now, ARGV[3])
              count = count + 1
              allowed = 1
            else
              -- the oldest entry leaves the window at oldest + window; from then on there is room
              local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              retry = tonumber(oldest[2]) + window - now
            end
            redis.call('PEXPIRE', KEYS[1], window)
            local newest = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            local reset = tonumber(newest[2]) + window - now
            return {allowed, limit - count, retry, reset}
            """);

    private final StatefulRedisConnection<String, String> connection;
    private final AtomicLong lastFailureLogNanos = new AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

    public StrictRedisLimiter(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        if (cost < 1) {
            throw new IllegalArgumentException("cost must be at least 1, was " + cost);
        }
        return switch (rule.algorithm()) {
            case TOKEN_BUCKET -> tokenBucket(rule, key, cost);
            case SLIDING_WINDOW_LOG -> slidingWindow(rule, key, cost);
            case LEAKY_BUCKET_QUEUE ->
                    throw new UnsupportedOperationException("Redis limiter does not support " + rule.algorithm() + " yet");
        };
    }

    private Decision tokenBucket(Rule rule, String key, int cost) {
        TokenBucketParams params = (TokenBucketParams) rule.params();
        long units = (long) params.cost() * cost;
        if (units > params.capacity()) {
            throw new IllegalArgumentException(
                    "request cost " + units + " exceeds capacity " + params.capacity() + " of rule " + rule.name());
        }

        List<Long> result = run(rule, TOKEN_BUCKET,
                List.of(RedisKeys.of(rule.ruleId(), key, "tb")),
                Long.toString(params.capacity()),
                Double.toString(params.refillPerSec()),
                Long.toString(units),
                Long.toString(keyTtlMillis(params)));
        if (result == null) {
            return Decision.failedOpen(params.capacity());
        }
        return toDecision(params.capacity(), result);
    }

    private Decision slidingWindow(Rule rule, String key, int cost) {
        SlidingWindowParams params = (SlidingWindowParams) rule.params();
        if (cost != 1) {
            throw new IllegalArgumentException("a sliding window counts requests, so cost must be 1, was " + cost);
        }

        List<Long> result = run(rule, SLIDING_WINDOW,
                List.of(RedisKeys.of(rule.ruleId(), key, "sw")),
                Long.toString(params.windowMs()),
                Long.toString(params.limit()),
                Long.toHexString(ThreadLocalRandom.current().nextLong())); // short unique id: memory is O(limit)
        if (result == null) {
            return Decision.failedOpen(params.limit());
        }
        return toDecision(params.limit(), result);
    }

    private static Decision toDecision(long limit, List<Long> result) {
        long remaining = result.get(1);
        long resetMs = result.get(3);
        if (result.get(0) == 1) {
            return Decision.allowed(limit, remaining, resetMs);
        }
        return Decision.rejected(limit, remaining, Math.max(1, result.get(2)), resetMs);
    }

    /** @return the script result, or {@code null} if Redis could not be reached (already logged) */
    private List<Long> run(Rule rule, LuaScript script, List<String> keys, String... args) {
        try {
            return script.run(connection, keys, args);
        } catch (RuntimeException e) {
            logFailure(rule, e);
            return null;
        }
    }

    /** An idle bucket is dropped once it would have refilled completely twice over, plus a second. */
    static long keyTtlMillis(TokenBucketParams params) {
        double fullRefillMs = Math.ceil(params.capacity() * 1000.0 / params.refillPerSec());
        return (long) Math.min(MAX_KEY_TTL_MS, fullRefillMs * 2 + 1000);
    }

    private void logFailure(Rule rule, RuntimeException e) {
        long now = System.nanoTime();
        long last = lastFailureLogNanos.get();
        if (now - last >= LOG_INTERVAL_NANOS && lastFailureLogNanos.compareAndSet(last, now)) {
            log.error("Redis limiter unavailable, letting requests through (rule '{}'): {}", rule.name(), e.toString());
        }
    }
}
