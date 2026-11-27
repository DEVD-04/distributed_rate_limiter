package com.ratelimit.gateway.ratelimit.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How gateway nodes count each other ({@code gateway.cluster.*}). Give every node the same values.
 *
 * @param heartbeatInterval how often a node reports itself to Redis
 * @param nodeTimeout       how long after its latest report a node still counts
 */
@ConfigurationProperties(prefix = "gateway.cluster")
public record ClusterProperties(
        @DefaultValue("1s") Duration heartbeatInterval,
        @DefaultValue("3s") Duration nodeTimeout) {
}
