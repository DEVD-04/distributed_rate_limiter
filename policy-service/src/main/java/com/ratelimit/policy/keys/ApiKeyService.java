package com.ratelimit.policy.keys;

import com.ratelimit.policy.admin.NotFoundException;
import com.ratelimit.policy.config.ConfigVersion;
import com.ratelimit.policy.rules.TierRepository;
import com.ratelimit.rules.ApiKeyHash;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API keys. The service generates each key, returns it once and stores only its hash, so a key cannot
 * be recovered from the database (a lost key is replaced, not looked up).
 */
@Service
public class ApiKeyService {

    /** What is stored about a key. */
    public record ApiKeyView(String keyHash, String userId, String tier, boolean active) {
    }

    /** The only time the key itself is ever shown. */
    public record CreatedApiKey(String apiKey, String keyHash, String userId, String tier) {

        @Override
        public String toString() {
            return "CreatedApiKey[apiKey=<hidden>, userId=" + userId + ", tier=" + tier + "]";
        }
    }

    private static final String PREFIX = "rlk_";
    private static final int RANDOM_BYTES = 32;

    private final JdbcClient jdbc;
    private final TierRepository tiers;
    private final ConfigVersion version;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcClient jdbc, TierRepository tiers, ConfigVersion version) {
        this.jdbc = jdbc;
        this.tiers = tiers;
        this.version = version;
    }

    public List<ApiKeyView> list() {
        return jdbc.sql("SELECT key_hash, user_id, tier, is_active FROM api_keys ORDER BY user_id, key_hash")
                .query((row, n) -> new ApiKeyView(row.getString("key_hash"), row.getString("user_id"),
                        row.getString("tier"), row.getBoolean("is_active")))
                .list();
    }

    @Transactional
    public CreatedApiKey create(String userId, String tier) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("field 'userId' is required");
        }
        if (tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("field 'tier' is required");
        }
        version.lockForChange();
        tiers.requireKnown(tier);

        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String apiKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = ApiKeyHash.of(apiKey);

        jdbc.sql("INSERT INTO api_keys (key_hash, user_id, tier) VALUES (:hash, :userId, :tier)")
                .param("hash", hash).param("userId", userId.trim()).param("tier", tier)
                .update();
        version.bump();
        return new CreatedApiKey(apiKey, hash, userId.trim(), tier);
    }

    /** Revoking a key that is already revoked changes nothing. */
    @Transactional
    public void revoke(String keyHash) {
        version.lockForChange();
        boolean active = jdbc.sql("SELECT is_active FROM api_keys WHERE key_hash = :hash")
                .param("hash", keyHash).query(Boolean.class).optional()
                .orElseThrow(() -> new NotFoundException("No API key with that hash."));
        if (active) {
            jdbc.sql("UPDATE api_keys SET is_active = false WHERE key_hash = :hash").param("hash", keyHash).update();
            version.bump();
        }
    }
}
