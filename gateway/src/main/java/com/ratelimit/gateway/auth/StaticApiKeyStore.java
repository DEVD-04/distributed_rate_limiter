package com.ratelimit.gateway.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** API keys from configuration. Replaced by a PostgreSQL-backed store in the rules phase. */
public class StaticApiKeyStore implements ApiKeyStore {

    private final Map<String, Identity> byHash = new HashMap<>();

    public StaticApiKeyStore(List<AuthProperties.ApiKey> keys) {
        for (AuthProperties.ApiKey key : keys) {
            if (key.keyHash() == null || !key.keyHash().matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("gateway.auth.api-keys[].key-hash must be a SHA-256 hex digest");
            }
            if (isBlank(key.userId()) || isBlank(key.tier())) {
                throw new IllegalArgumentException("gateway.auth.api-keys[] needs user-id and tier");
            }
            if (key.active()) {
                String hash = key.keyHash().toLowerCase(Locale.ROOT);
                byHash.put(hash, Identity.apiKey(key.userId(), key.tier(), hash));
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public Optional<Identity> findByHash(String keyHash) {
        return Optional.ofNullable(byHash.get(keyHash));
    }
}
