package com.ratelimit.gateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/**
 * Who a request belongs to, as established by {@link AuthFilter}.
 *
 * @param userId   stable user identifier ({@code sub} claim or API key owner)
 * @param tier     tier name used to select rate-limit rules
 * @param method   how the caller authenticated
 * @param apiKeyId for API-key callers, the SHA-256 hash of the key (never the key itself), which is
 *                 what rate limits and logs refer to; {@code null} for JWT callers
 */
public record Identity(String userId, String tier, Method method, String apiKeyId) {

    public enum Method { JWT, API_KEY }

    public Identity {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(method, "method");
        if ((method == Method.API_KEY) != (apiKeyId != null)) {
            throw new IllegalArgumentException("apiKeyId must be set exactly for API_KEY identities");
        }
    }

    public static Identity jwt(String userId, String tier) {
        return new Identity(userId, tier, Method.JWT, null);
    }

    public static Identity apiKey(String userId, String tier, String apiKeyId) {
        return new Identity(userId, tier, Method.API_KEY, apiKeyId);
    }

    private static final String ATTRIBUTE = Identity.class.getName();

    static void attachTo(HttpServletRequest request, Identity identity) {
        request.setAttribute(ATTRIBUTE, identity);
    }

    /** The authenticated identity, or {@code null} if the request is unauthenticated or public. */
    public static Identity of(HttpServletRequest request) {
        return (Identity) request.getAttribute(ATTRIBUTE);
    }
}
