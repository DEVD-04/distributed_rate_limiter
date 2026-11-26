package com.ratelimit.policy.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the admin API ({@code policy.*}).
 *
 * @param adminToken the secret every admin request must send in {@code X-Admin-Token}. There is no
 *                   default: anyone who can use this API can remove every rate limit, so the service
 *                   refuses to start without a reasonably long one.
 */
@ConfigurationProperties(prefix = "policy")
public record AdminProperties(@DefaultValue("") String adminToken) {

    public static final int MIN_TOKEN_LENGTH = 16;

    public AdminProperties {
        if (adminToken.isBlank() || adminToken.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("policy.admin-token (env POLICY_ADMIN_TOKEN) must be set, at least "
                    + MIN_TOKEN_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return "AdminProperties[adminToken=<hidden>]"; // never let the secret reach a log
    }
}
