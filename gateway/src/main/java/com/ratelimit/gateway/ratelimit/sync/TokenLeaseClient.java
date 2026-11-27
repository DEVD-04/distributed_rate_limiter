package com.ratelimit.gateway.ratelimit.sync;

import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.redis.LuaScript;
import com.ratelimit.gateway.redis.RedisKeys;
import com.ratelimit.rules.TokenBucketParams;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Borrows tokens for {@code LOCAL_SYNC} from the shared bucket in Redis. A node may spend only what it
 * was granted here, and every grant has already been taken out of the shared bucket, so tokens are
 * never created by being lent out.
 *
 * <p>The shared bucket is the same one {@code STRICT} uses (same key, same fields), so a rule switched
 * between the two modes keeps its balance.
 */
public class TokenLeaseClient {

    /**
     * Refills the shared bucket, takes back unused tokens and lends out whole tokens.
     * KEYS[1] bucket hash; ARGV: capacity, refill_per_sec, want, need, returned, node_count, ttl_ms.
     * Returns {granted, whole tokens left, ms until the bucket can cover another such need, ms until full}.
     *
     * <p>A node normally gets at most its fair share, ceil(whole tokens / nodes), so one node cannot empty the
     * bucket in a single call. {@code need} is what a waiting request is short of. If the bucket holds that
     * much, at least that much is granted even above the share: otherwise a request costing more than a
     * share would be refused although the bucket could pay for it. If the bucket does not hold that much,
     * nothing is granted, so tokens collect in the shared bucket instead of being split into amounts that
     * are too small to use. With need 0 (a top-up) any part of the share is useful.
     */
    static final LuaScript LEASE = new LuaScript("token_lease", """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local cap, rate = tonumber(ARGV[1]), tonumber(ARGV[2])
            local want, need, returned = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])
            local nodes, ttl = tonumber(ARGV[6]), tonumber(ARGV[7])
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens, ts = tonumber(d[1]), tonumber(d[2])
            if tokens == nil then tokens = cap; ts = now end
            -- capped after adding what came back, so a return can never push the bucket above capacity
            tokens = math.min(cap, tokens + math.max(0, now - ts) * rate / 1000 + returned)
            -- 1e-9 absorbs floating-point rounding, as in the STRICT token bucket
            local whole = math.floor(tokens + 1e-9)
            local grant = 0
            if whole >= need then
              local share = math.ceil(whole / nodes) -- of whole tokens: a refill fraction must not raise the share
              grant = math.max(need, math.min(want, share, whole))
            end
            tokens = math.max(0, tokens - grant)
            -- never move the timestamp backwards, or the same elapsed time could be credited twice
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', math.max(now, ts))
            redis.call('PEXPIRE', KEYS[1], ttl)
            local target = math.max(need, 1)
            local wait = math.max(0, math.ceil((target - tokens - 1e-9) * 1000 / rate))
            local full = math.ceil((cap - tokens) * 1000 / rate)
            return {grant, math.floor(tokens + 1e-9), wait, full}
            """);

    /**
     * The answer to one lease call.
     *
     * @param granted         whole tokens now owned by the caller
     * @param sharedRemaining whole tokens left in the shared bucket
     * @param waitMs          0 if the shared bucket can cover the same need again (at least one token),
     *                        otherwise how long until it can
     * @param fullMs          how long until the shared bucket is full if nobody takes anything
     */
    public record Lease(long granted, long sharedRemaining, long waitMs, long fullMs) {
    }

    private final Supplier<StatefulRedisConnection<String, String>> connection;

    /** @param connection asked for on every call; it may throw while Redis has not been reached */
    public TokenLeaseClient(Supplier<StatefulRedisConnection<String, String>> connection) {
        this.connection = connection;
    }

    /**
     * One call to the shared bucket of a (rule, subject).
     *
     * <p>A call that fails may or may not have run in Redis. The caller must therefore not send the same
     * returned tokens again: crediting them twice could admit more than the limit, while losing them only
     * admits less. For the same reason a grant whose reply was lost is simply lost.
     *
     * @param want      tokens the node would like; raised to {@code need} if lower. 0 with need 0 only returns tokens
     * @param need      tokens a waiting request is short of, or 0 for a top-up
     * @param returned  borrowed tokens the node is giving back unused
     * @param nodeCount gateway nodes currently sharing the bucket, which sets the fair share
     * @throws RuntimeException if Redis cannot be reached or does not answer in time
     */
    public Lease lease(long ruleId, String subject, TokenBucketParams params,
            long want, long need, long returned, int nodeCount) {
        if (want < 0 || need < 0 || returned < 0) {
            throw new IllegalArgumentException(
                    "want, need and returned must not be negative: " + want + ", " + need + ", " + returned);
        }
        if (need > params.capacity()) {
            throw new IllegalArgumentException("need " + need + " exceeds capacity " + params.capacity());
        }
        if (nodeCount < 1) {
            throw new IllegalArgumentException("node count must be at least 1, was " + nodeCount);
        }
        List<Long> result = LEASE.run(connection.get(),
                List.of(RedisKeys.of(ruleId, subject, "tb")),
                Long.toString(params.capacity()),
                Double.toString(params.refillPerSec()),
                Long.toString(Math.max(want, need)),
                Long.toString(need),
                Long.toString(returned),
                Integer.toString(nodeCount),
                Long.toString(StrictRedisLimiter.keyTtlMillis(params)));
        return new Lease(result.get(0), result.get(1), result.get(2), result.get(3));
    }
}
