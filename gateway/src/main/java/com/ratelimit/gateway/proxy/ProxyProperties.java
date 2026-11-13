package com.ratelimit.gateway.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Routing and upstream settings ({@code gateway.proxy.*}).
 *
 * @param routes         path patterns mapped to downstream services
 * @param connectTimeout how long to wait to open a connection to an instance
 * @param readTimeout    how long to wait for an instance to start answering (response headers);
 *                       must exceed the largest leaky-bucket wait of any rule, and the load
 *                       balancer's own timeout must exceed this
 * @param ejection       passive health-check settings
 */
@ConfigurationProperties(prefix = "gateway.proxy")
public record ProxyProperties(
        @DefaultValue List<RouteEntry> routes,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue Ejection ejection) {

    /**
     * @param pattern   Ant-style path pattern, e.g. {@code /api/images/**}; the path is forwarded unchanged
     * @param service   service name (unique); used in logs and metrics
     * @param instances base URLs of the instances, e.g. {@code http://image-1:8080}
     */
    public record RouteEntry(String pattern, String service, List<URI> instances) {
    }

    /**
     * @param failureThreshold consecutive failures after which an instance is taken out of rotation
     * @param cooldown         how long it stays out before being tried again
     */
    public record Ejection(
            @DefaultValue("3") int failureThreshold,
            @DefaultValue("10s") Duration cooldown) {

        public Ejection {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("failure-threshold must be at least 1");
            }
            if (cooldown.isNegative() || cooldown.isZero()) {
                throw new IllegalArgumentException("cooldown must be positive");
            }
        }
    }
}
