package com.ratelimit.gateway.ratelimit;

import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.rules.Algorithm;
import com.ratelimit.gateway.rules.Mode;
import com.ratelimit.gateway.rules.Rule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Sends each request to the limiter that fits its rule: STRICT rules to Redis (exact, shared by all
 * nodes) when Redis is configured, everything else to the in-memory limiter.
 *
 * <p>Until the LOCAL_SYNC phase, a LOCAL_SYNC rule is limited purely in memory on each node, so a
 * cluster of N nodes admits up to N times the limit. That is the documented trade-off of local
 * limiting, and the reason STRICT is the default.
 */
public class RateLimiterRouter implements RateLimiter {

    private final RateLimiter inMemory;
    private final RateLimiter redis; // null when Redis is not configured

    /** @param redis the Redis-backed limiter, or {@code null} to limit everything in memory */
    public RateLimiterRouter(RateLimiter inMemory, RateLimiter redis) {
        this.inMemory = inMemory;
        this.redis = redis;
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        return servedByRedis(rule, redis != null) ? redis.acquire(rule, key, cost) : inMemory.acquire(rule, key, cost);
    }

    private static boolean servedByRedis(Rule rule, boolean redisAvailable) {
        return redisAvailable && rule.mode() == Mode.STRICT;
    }

    /**
     * Rules that cannot be served with the limiters available, each described for an operator.
     * Checked at startup, so a misconfiguration stops the gateway instead of failing on the first request.
     */
    public static List<String> unservable(Collection<Rule> rules, boolean redisAvailable) {
        List<String> problems = new ArrayList<>();
        for (Rule rule : rules) {
            if (servedByRedis(rule, redisAvailable) || rule.algorithm() == Algorithm.TOKEN_BUCKET) {
                continue; // Redis handles every algorithm; the in-memory limiter handles the token bucket
            }
            if (rule.mode() == Mode.STRICT) {
                problems.add("rule '" + rule.name() + "' uses " + rule.algorithm()
                        + ", which needs Redis: set gateway.redis.url");
            } else {
                problems.add("rule '" + rule.name() + "': mode LOCAL_SYNC is not available for " + rule.algorithm()
                        + " yet (token bucket only); use mode STRICT");
            }
        }
        return problems;
    }
}
