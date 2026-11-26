package com.ratelimit.gateway.auth;

import com.ratelimit.rules.ApiKeyHash;
import java.util.Optional;

/** Resolves an API key to an identity. Keys are looked up by their SHA-256 hash. */
public interface ApiKeyStore {

    /** @param keyHash lower-case hex SHA-256 of the presented key */
    Optional<Identity> findByHash(String keyHash);

    static String sha256Hex(String apiKey) {
        return ApiKeyHash.of(apiKey);
    }
}
