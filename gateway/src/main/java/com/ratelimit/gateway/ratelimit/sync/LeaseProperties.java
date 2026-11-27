package com.ratelimit.gateway.ratelimit.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How {@code LOCAL_SYNC} rules borrow tokens ({@code gateway.lease.*}); see {@link LeaseSettings}.
 */
@ConfigurationProperties(prefix = "gateway.lease")
public record LeaseProperties(
        @DefaultValue("1") long minBatch,
        @DefaultValue("1s") Duration horizon,
        @DefaultValue("0.3") double refillBelow) {

    public LeaseSettings settings() {
        return new LeaseSettings(minBatch, horizon, refillBelow);
    }
}
