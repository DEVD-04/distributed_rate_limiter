package com.ratelimit.gateway.ratelimit.web;

import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.local.InMemoryRateLimiter;
import com.ratelimit.gateway.rules.RulesProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    /** Single-node, in-memory limiter. Replaced by the router over Redis-backed limiters in later phases. */
    @Bean
    RateLimiter rateLimiter(RulesProperties properties) {
        return new InMemoryRateLimiter(properties.maxKeys());
    }
}
