package com.ratelimit.rules.snapshot;

import com.ratelimit.rules.Rule;
import java.util.List;

/**
 * The whole configuration a gateway needs, at one version: every active rule and every active API key.
 * The policy service publishes it and gateways load it as a unit, so a gateway never holds a mix of old
 * and new settings. A revoked key or deactivated rule is simply absent.
 *
 * @param version the configuration version it was read at
 * @param rules   all active rules
 * @param apiKeys all active API keys, as hashes
 */
public record ConfigSnapshot(long version, List<Rule> rules, List<ApiKeyEntry> apiKeys) {

    /**
     * @param keyHash lower-case hex SHA-256 of the key (see {@code ApiKeyHash})
     * @param userId  who the key belongs to
     * @param tier    the tier used to select rules
     */
    public record ApiKeyEntry(String keyHash, String userId, String tier) {

        public ApiKeyEntry {
            if (keyHash == null || !keyHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("API key entry needs a lower-case SHA-256 hash");
            }
            if (userId == null || userId.isBlank() || tier == null || tier.isBlank()) {
                throw new IllegalArgumentException("API key entry needs a user and a tier");
            }
        }
    }

    public ConfigSnapshot {
        if (version < 1) {
            throw new IllegalArgumentException("snapshot version must be positive, was " + version);
        }
        rules = List.copyOf(rules);
        apiKeys = List.copyOf(apiKeys);
    }
}
