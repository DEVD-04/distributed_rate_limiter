package com.ratelimit.gateway.web;

import com.ratelimit.gateway.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

    @Bean
    TrustedProxies trustedProxies(GatewayProperties properties) {
        return TrustedProxies.of(properties.trustedProxies());
    }

    @Bean
    ClientIpResolver clientIpResolver(TrustedProxies trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }
}
