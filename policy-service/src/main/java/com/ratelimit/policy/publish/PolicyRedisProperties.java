package com.ratelimit.policy.publish;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Redis settings for publishing ({@code policy.redis.*}).
 *
 * @param url            where gateways read the configuration; empty turns publishing off
 * @param commandTimeout how long one publish or read may take
 * @param connectTimeout how long to wait when opening the connection
 */
@ConfigurationProperties(prefix = "policy.redis")
public record PolicyRedisProperties(
        @DefaultValue("") String url,
        @DefaultValue("2s") Duration commandTimeout,
        @DefaultValue("1s") Duration connectTimeout) {

    public boolean enabled() {
        return !url.isBlank();
    }
}
