package com.ratelimit.gateway.ratelimit.web;

import com.ratelimit.gateway.ratelimit.RateLimiterRouter;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.local.InMemoryRateLimiter;
import com.ratelimit.gateway.ratelimit.strict.StrictRedisLimiter;
import com.ratelimit.gateway.redis.RedisConnector;
import com.ratelimit.gateway.rules.LiveRuleResolver;
import com.ratelimit.gateway.rules.RulesProperties;
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
     * cannot be served by what is available. (Published rules are checked as they are loaded, and
     * refused rather than failing the gateway.)
     */
    @Bean
    RateLimiter rateLimiter(RulesProperties properties, LiveRuleResolver rules, ObjectProvider<RedisConnector> redis) {
        RedisConnector connector = redis.getIfAvailable();

        List<String> problems = RateLimiterRouter.unservable(rules.configured().rules(), connector != null);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Some rate-limit rules cannot be served:\n - " + String.join("\n - ", problems));
        }

        RateLimiter strict = connector == null ? null
                : new StrictRedisLimiter(connector::connection, properties.maxWaiting());
        log.info("Rate limiting: {}", strict == null
                ? "everything in memory (no gateway.redis.url set)"
                : "STRICT rules in Redis, other rules in memory");
        return new RateLimiterRouter(new InMemoryRateLimiter(properties.maxKeys()), strict);
    }
}
