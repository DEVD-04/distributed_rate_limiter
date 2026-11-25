package com.ratelimit.gateway.ratelimit;

import com.ratelimit.gateway.rules.Algorithm;
import com.ratelimit.gateway.rules.FailureMode;
import com.ratelimit.gateway.rules.LeakyBucketParams;
import com.ratelimit.gateway.rules.LimitBy;
import com.ratelimit.gateway.rules.Mode;
import com.ratelimit.gateway.rules.Rule;
import com.ratelimit.gateway.rules.SlidingWindowParams;
import com.ratelimit.gateway.rules.TokenBucketParams;

/** Builders for rules used across limiter tests. */
public final class TestRules {

    private TestRules() {
    }

    public static Rule leakyBucket(long ruleId, double drainPerSec, long queueCapacity) {
        return new Rule(ruleId, "rule-" + ruleId, LimitBy.USER, null, null, "/api/**", null,
                Algorithm.LEAKY_BUCKET_QUEUE, Mode.STRICT, FailureMode.LOCAL_FALLBACK,
                new LeakyBucketParams(drainPerSec, queueCapacity), 0, 1);
    }

    public static Rule slidingWindow(long ruleId, long limit, long windowMs) {
        return new Rule(ruleId, "rule-" + ruleId, LimitBy.USER, null, null, "/api/**", null,
                Algorithm.SLIDING_WINDOW_LOG, Mode.STRICT, FailureMode.LOCAL_FALLBACK,
                new SlidingWindowParams(limit, windowMs), 0, 1);
    }

    public static Rule tokenBucket(long ruleId, long capacity, double refillPerSec) {
        return tokenBucket(ruleId, capacity, refillPerSec, 1);
    }

    public static Rule tokenBucket(long ruleId, long capacity, double refillPerSec, int cost) {
        return new Rule(ruleId, "rule-" + ruleId, LimitBy.USER, null, null, "/api/**", null,
                Algorithm.TOKEN_BUCKET, Mode.STRICT, FailureMode.LOCAL_FALLBACK,
                new TokenBucketParams(capacity, refillPerSec, cost), 0, 1);
    }
}
