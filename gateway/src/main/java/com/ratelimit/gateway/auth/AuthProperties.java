package com.ratelimit.gateway.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings ({@code gateway.auth.*}).
 *
 * @param jwt         RS256 token validation settings
 * @param apiKeys     static API key table (a database-backed store replaces it in a later phase)
 * @param publicPaths Ant-style path patterns that skip authentication (e.g. {@code /healthz})
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue List<ApiKey> apiKeys,
        @DefaultValue List<String> publicPaths) {

    /**
     * @param issuer     required {@code iss} value
     * @param audience   required {@code aud} value
     * @param leeway     clock skew tolerated on {@code exp} / {@code nbf}
     * @param publicKeys {@code kid} to RSA public key, each either an inline PEM block or a
     *                   resource location ({@code classpath:...}, {@code file:...}). Tokens must
     *                   carry a {@code kid} that is in this map.
     */
    public record Jwt(
            @DefaultValue("") String issuer,
            @DefaultValue("") String audience,
            @DefaultValue("30s") Duration leeway,
            @DefaultValue Map<String, String> publicKeys) {
    }

    /**
     * @param keyHash lower-case hex SHA-256 of the API key (the key itself is never stored)
     * @param userId  user the key belongs to
     * @param tier    tier name used to select rate-limit rules
     * @param active  inactive keys are rejected
     */
    public record ApiKey(
            String keyHash,
            String userId,
            String tier,
            @DefaultValue("true") boolean active) {
    }
}
