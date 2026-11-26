package com.ratelimit.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.api.Decision;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LeakyBucketParams;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.Params;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.SlidingWindowParams;
import com.ratelimit.rules.TokenBucketParams;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimiterRouterTests {

    private static final long FROM_MEMORY = 222;
    private static final long FROM_REDIS = 111;

    private static final RateLimiter MEMORY = (rule, key, cost) -> Decision.allowed(FROM_MEMORY, 0, 0);
    private static final RateLimiter REDIS = (rule, key, cost) -> Decision.allowed(FROM_REDIS, 0, 0);

    private static Rule rule(String name, Algorithm algorithm, Mode mode, Params params) {
        return new Rule(1, name, LimitBy.USER, null, null, "/a", null, algorithm, mode, FailureMode.LOCAL_FALLBACK, params, 0, 1);
    }

    private static Rule tokenBucket(Mode mode) {
        return rule("tb", Algorithm.TOKEN_BUCKET, mode, new TokenBucketParams(5, 1));
    }

    private static long limitFor(RateLimiter router, Rule rule) {
        return router.acquire(rule, "alice", 1).limit();
    }

    @Test
    void strictRulesGoToRedisAndLocalSyncRulesStayInMemory() {
        RateLimiterRouter router = new RateLimiterRouter(MEMORY, REDIS);

        assertThat(limitFor(router, tokenBucket(Mode.STRICT))).isEqualTo(FROM_REDIS);
        assertThat(limitFor(router, tokenBucket(Mode.LOCAL_SYNC))).isEqualTo(FROM_MEMORY);
    }

    @Test
    void withoutRedisEverythingStaysInMemory() {
        RateLimiterRouter router = new RateLimiterRouter(MEMORY, null);

        assertThat(limitFor(router, tokenBucket(Mode.STRICT))).isEqualTo(FROM_MEMORY);
        assertThat(limitFor(router, tokenBucket(Mode.LOCAL_SYNC))).isEqualTo(FROM_MEMORY);
    }

    @Test
    void theKeyAndCostArePassedThrough() {
        RateLimiter echo = (rule, key, cost) -> Decision.allowed(key.length(), cost, 0);

        Decision d = new RateLimiterRouter(MEMORY, echo).acquire(tokenBucket(Mode.STRICT), "alice", 3);

        assertThat(d.limit()).isEqualTo(5);
        assertThat(d.remaining()).isEqualTo(3);
    }

    // ---- what can be served ----

    private static final Rule SLIDING_STRICT = rule("sliding", Algorithm.SLIDING_WINDOW_LOG, Mode.STRICT, new SlidingWindowParams(5, 1000));
    private static final Rule LEAKY_STRICT = rule("leaky", Algorithm.LEAKY_BUCKET_QUEUE, Mode.STRICT, new LeakyBucketParams(10, 5));
    private static final Rule LEAKY_LOCAL = rule("leaky-local", Algorithm.LEAKY_BUCKET_QUEUE, Mode.LOCAL_SYNC, new LeakyBucketParams(10, 5));

    @Test
    void everyRuleIsServableWithRedis() {
        List<Rule> rules = List.of(tokenBucket(Mode.STRICT), tokenBucket(Mode.LOCAL_SYNC), SLIDING_STRICT, LEAKY_STRICT);

        assertThat(RateLimiterRouter.unservable(rules, true)).isEmpty();
    }

    @Test
    void withoutRedisOnlyTokenBucketRulesAreServable() {
        List<String> problems = RateLimiterRouter.unservable(
                List.of(tokenBucket(Mode.STRICT), tokenBucket(Mode.LOCAL_SYNC), SLIDING_STRICT, LEAKY_STRICT), false);

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("'sliding'").contains("SLIDING_WINDOW_LOG").contains("gateway.redis.url");
        assertThat(problems.get(1)).contains("'leaky'").contains("LEAKY_BUCKET_QUEUE").contains("gateway.redis.url");
    }

    @Test
    void localSyncIsNotAvailableForTheLeakyBucketYetEvenWithRedis() {
        List<String> problems = RateLimiterRouter.unservable(List.of(LEAKY_LOCAL), true);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("'leaky-local'").contains("LOCAL_SYNC").contains("use mode STRICT");
    }
}
