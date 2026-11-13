package com.ratelimit.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fault-injection settings for one mock instance. Every value can be overridden per instance with
 * a property or environment variable (e.g. {@code MOCK_LATENCY_MS=200}).
 *
 * @param instanceId    name echoed in responses so tests can see which instance answered
 * @param latencyMs     fixed delay added to every request
 * @param latencyJitterMs extra random delay, uniformly in [0, latencyJitterMs]
 * @param errorRate     probability in [0, 1] that a request fails
 * @param errorStatus   HTTP status returned for an injected failure
 */
@ConfigurationProperties(prefix = "mock")
public record MockProperties(
        @DefaultValue("mock") String instanceId,
        @DefaultValue("0") long latencyMs,
        @DefaultValue("0") long latencyJitterMs,
        @DefaultValue("0") double errorRate,
        @DefaultValue("500") int errorStatus) {
    
    // rejects bad values at startup
    public MockProperties {
        if (latencyMs < 0 || latencyJitterMs < 0) {
            throw new IllegalArgumentException("mock latency values must not be negative");
        }
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException("mock.error-rate must be between 0 and 1, was " + errorRate);
        }
        if (errorStatus < 400 || errorStatus > 599) {
            throw new IllegalArgumentException("mock.error-status must be 4xx or 5xx, was " + errorStatus);
        }
    }
}
