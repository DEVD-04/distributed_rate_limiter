package com.ratelimit.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level gateway settings. Later phases add their own nested sections.
 *
 * @param trustedProxies IPs or CIDR ranges (e.g. {@code 10.0.0.0/8}, {@code 172.18.0.2}) of the
 *                       reverse proxies / load balancers whose {@code X-Forwarded-For} header is
 *                       believed. Empty (the default) trusts nobody: the socket address is used.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(@DefaultValue List<String> trustedProxies) {
}
