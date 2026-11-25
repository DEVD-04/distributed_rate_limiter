package com.ratelimit.gateway.ratelimit.web;

import com.ratelimit.gateway.ratelimit.RateLimiterRouter;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.local.InMemoryRateLimiter;
import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.rules.RuleResolver;
import com.ratelimit.gateway.rules.RuleSet;
import com.ratelimit.gateway.rules.RulesProperties;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * The one {@link RateLimiter} the filters use: a router over the in-memory limiter and, when
     * {@code gateway.redis.url} is set, the Redis limiter. Fails startup if any configured rule
     * cannot be served by what is available.
     */
    @Bean
    RateLimiter rateLimiter(RulesProperties properties, RuleResolver rules,
            ObjectProvider<StatefulRedisConnection<String, String>> redisConnection) {
        StatefulRedisConnection<String, String> connection = redisConnection.getIfAvailable();

        if (rules instanceof RuleSet ruleSet) {
            List<String> problems = RateLimiterRouter.unservable(ruleSet.rules(), connection != null);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Some rate-limit rules cannot be served:\n - " + String.join("\n - ", problems));
            }
        }

        RateLimiter strict = connection == null ? null : new StrictRedisLimiter(connection, properties.maxWaiting());
        log.info("Rate limiting: {}", strict == null
                ? "everything in memory (no gateway.redis.url set)"
                : "STRICT rules in Redis, other rules in memory");
        return new RateLimiterRouter(new InMemoryRateLimiter(properties.maxKeys()), strict);
    }
}
