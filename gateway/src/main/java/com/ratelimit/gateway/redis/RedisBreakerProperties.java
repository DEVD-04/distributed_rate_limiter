package com.ratelimit.gateway.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * When to stop calling Redis for a while ({@code gateway.redis.breaker.*}); see {@link RedisBreaker}.
 *
 * @param window               how many of the latest calls are looked at; nothing opens before there are that many
 * @param failureRateThreshold the percentage of those that must have failed to open the breaker
 * @param openFor              how long no calls are made before Redis is tried again
 * @param probes               how many trial calls then decide whether to close it or open it again
 */
@ConfigurationProperties(prefix = "gateway.redis.breaker")
public record RedisBreakerProperties(
        @DefaultValue("20") int window,
        @DefaultValue("50") int failureRateThreshold,
        @DefaultValue("5s") Duration openFor,
        @DefaultValue("3") int probes) {

    public RedisBreakerProperties {
        if (window < 1) {
            throw new IllegalArgumentException("gateway.redis.breaker.window must be at least 1, was " + window);
        }
        if (failureRateThreshold < 1 || failureRateThreshold > 100) {
            throw new IllegalArgumentException(
                    "gateway.redis.breaker.failure-rate-threshold must be 1 to 100, was " + failureRateThreshold);
        }
        if (openFor.toMillis() < 1) {
            throw new IllegalArgumentException("gateway.redis.breaker.open-for must be at least 1 ms, was " + openFor);
        }
        if (probes < 1) {
            throw new IllegalArgumentException("gateway.redis.breaker.probes must be at least 1, was " + probes);
        }
    }
}
