package com.ratelimit.gateway.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Redis settings ({@code gateway.redis.*}).
 *
 * @param url            e.g. {@code redis://localhost:6379}; empty means Redis is not used and every rule
 *                       is limited in memory
 * @param commandTimeout how long one Redis call may take before it fails (the limiter sits in the request path)
 * @param connectTimeout how long to wait when opening the connection
 */
@ConfigurationProperties(prefix = "gateway.redis")
public record RedisProperties(
        @DefaultValue("") String url,
        @DefaultValue("15ms") Duration commandTimeout,
        @DefaultValue("1s") Duration connectTimeout) {

    public boolean enabled() {
        return !url.isBlank();
    }
}
