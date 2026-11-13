package com.ratelimit.gateway.auth;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtValidator jwtValidator(AuthProperties properties, Clock clock) {
        AuthProperties.Jwt jwt = properties.jwt();
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        jwt.publicKeys().forEach((kid, source) -> {
            try {
                keys.put(kid, PublicKeyLoader.load(source));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("gateway.auth.jwt.public-keys[" + kid + "]: " + e.getMessage(), e);
            }
        });
        if (!keys.isEmpty() && (jwt.issuer().isBlank() || jwt.audience().isBlank())) {
            throw new IllegalStateException(
                    "gateway.auth.jwt.issuer and audience are required when public keys are configured");
        }
        return new JwtValidator(keys, jwt.issuer(), jwt.audience(), jwt.leeway(), clock);
    }

    @Bean
    ApiKeyStore apiKeyStore(AuthProperties properties) {
        return new StaticApiKeyStore(properties.apiKeys());
    }
}
