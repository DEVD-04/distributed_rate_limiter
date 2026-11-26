package com.ratelimit.gateway.auth;

import com.ratelimit.gateway.rules.LiveRuleResolver;
import com.ratelimit.rules.snapshot.ConfigSnapshot;
import java.util.Map;
import java.util.Optional;

/**
 * API keys from the published configuration, falling back to the ones in {@code application.yml} only
 * while no published configuration is in force (no Redis, or nothing usable loaded yet).
 *
 * <p>Published keys <em>replace</em> the configured ones; the two are never combined. If they were, a
 * key revoked through the admin API would keep working for as long as it was also listed in a
 * gateway's configuration.
 *
 * <p>Every lookup reads whatever is in force at that moment, so a newly published key works, and a
 * revoked one stops working, as soon as the gateway applies the snapshot.
 */
public class LiveApiKeyStore implements ApiKeyStore {

    private final ApiKeyStore configured;
    private final LiveRuleResolver published;

    public LiveApiKeyStore(ApiKeyStore configured, LiveRuleResolver published) {
        this.configured = configured;
        this.published = published;
    }

    @Override
    public Optional<Identity> findByHash(String keyHash) {
        Optional<Map<String, ConfigSnapshot.ApiKeyEntry>> keys = published.publishedApiKeys();
        if (keys.isEmpty()) {
            return configured.findByHash(keyHash);
        }
        // Published configuration is in force: it alone decides. An unknown hash is simply unknown.
        ConfigSnapshot.ApiKeyEntry key = keys.get().get(keyHash);
        return key == null ? Optional.empty() : Optional.of(Identity.apiKey(key.userId(), key.tier(), keyHash));
    }
}
