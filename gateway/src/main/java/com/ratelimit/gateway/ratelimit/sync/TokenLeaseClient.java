package com.ratelimit.gateway.ratelimit.sync;

import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.redis.LuaScript;
import com.ratelimit.gateway.redis.RedisBreaker;
import com.ratelimit.gateway.redis.RedisKeys;
import com.ratelimit.rules.TokenBucketParams;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Borrows tokens for {@code LOCAL_SYNC} from the shared bucket in Redis. A node may spend only what it
 * was granted here, and every grant has already been taken out of the shared bucket, so tokens are
 * never created by being lent out.
 *
 * <p>The shared bucket is the same one {@code STRICT} uses (same key, same fields), so a rule switched
 * between the two modes keeps its balance.
 */
public class TokenLeaseClient implements LeaseSource {

    /** Far longer than a call can stay pending, which is what bounds how late it could be sent again. */
    private static final long RETURN_MARKER_TTL_MS = 60_000;

    /**
     * Refills the shared bucket, takes back unused tokens and lends out whole tokens.
     * KEYS[1] bucket hash, KEYS[2] marker of this call's return;
     * ARGV: capacity, refill_per_sec, want, need, returned, node_count, ttl_ms, marker_ttl_ms.
     * Returns {granted, whole tokens left, ms until the bucket can cover another such need, ms until full}.
     *
     * <p>The client library sends a call again if the connection breaks before the reply and comes back
     * while the call is still pending, even though Redis may already have run it. Running the borrowing
     * twice only loses the first grant. Crediting a return twice would create tokens, so a return is
     * credited only if its marker, unique to the call, is not there yet.
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
            if returned > 0 and not redis.call('SET', KEYS[2], 1, 'NX', 'PX', tonumber(ARGV[8])) then
              returned = 0 -- this very call already ran, and credited it then
            end
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

    private final Supplier<StatefulRedisConnection<String, String>> connection;

    private final RedisBreaker breaker;

    /** @param connection asked for on every call; it may throw while Redis has not been reached */
    public TokenLeaseClient(Supplier<StatefulRedisConnection<String, String>> connection) {
        this(connection, RedisBreaker.disabled());
    }

    /** @param breaker decides whether Redis is called at all; see {@link LeaseSource#lease} */
    public TokenLeaseClient(Supplier<StatefulRedisConnection<String, String>> connection, RedisBreaker breaker) {
        this.connection = connection;
        this.breaker = breaker;
    }

    @Override
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
        var random = ThreadLocalRandom.current();
        String callId = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
        List<Long> result = breaker.call(() -> LEASE.run(connection.get(),
                List.of(RedisKeys.of(ruleId, subject, "tb"), RedisKeys.of(ruleId, subject, "ret:" + callId)),
                Long.toString(params.capacity()),
                Double.toString(params.refillPerSec()),
                Long.toString(Math.max(want, need)),
                Long.toString(need),
                Long.toString(returned),
                Integer.toString(nodeCount),
                Long.toString(StrictRedisLimiter.keyTtlMillis(params)),
                Long.toString(RETURN_MARKER_TTL_MS)));
        return new Lease(result.get(0), result.get(1), result.get(2), result.get(3));
    }
}
