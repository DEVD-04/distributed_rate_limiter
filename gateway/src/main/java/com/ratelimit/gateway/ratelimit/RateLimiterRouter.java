package com.ratelimit.gateway.ratelimit;

import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Rule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Sends each request to the limiter that fits its rule. With Redis configured, STRICT rules are decided
 * in Redis (exact, one call per request) and LOCAL_SYNC rules from tokens each node borrows from Redis.
 * Without it there is nothing to share, and everything is limited in this node's memory, so several
 * gateways would each admit the whole limit.
 */
public class RateLimiterRouter implements RateLimiter {

    private final RateLimiter inMemory;
    private final RateLimiter strict;    // null when Redis is not configured
    private final RateLimiter localSync; // null when Redis is not configured

    /** Everything in this node's memory: for a gateway without Redis. */
    public RateLimiterRouter(RateLimiter inMemory) {
        this.inMemory = inMemory;
        this.strict = null;
        this.localSync = null;
    }

    /** A gateway with Redis: one limiter for each mode. */
    public RateLimiterRouter(RateLimiter strict, RateLimiter localSync) {
        this.inMemory = null;
        this.strict = Objects.requireNonNull(strict);
        this.localSync = Objects.requireNonNull(localSync);
    }

    @Override
    public Decision acquire(Rule rule, String key, int cost) {
        if (inMemory != null) {
            return inMemory.acquire(rule, key, cost);
        }
        return (rule.mode() == Mode.STRICT ? strict : localSync).acquire(rule, key, cost);
    }

    /**
     * Rules that cannot be served with the limiters available, each described for an operator.
     * Checked at startup, so a misconfiguration stops the gateway instead of failing on the first request.
     */
    public static List<String> unservable(Collection<Rule> rules, boolean redisAvailable) {
        List<String> problems = new ArrayList<>();
        for (Rule rule : rules) {
            if ((redisAvailable && rule.mode() == Mode.STRICT) || Rule.servableInMemory(rule.algorithm())) {
                continue; // STRICT in Redis handles every algorithm; both other ways handle only the token bucket
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
