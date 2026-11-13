package com.ratelimit.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Resolves an API key to an identity. Keys are looked up by their SHA-256 hash. */
public interface ApiKeyStore {

    /** @param keyHash lower-case hex SHA-256 of the presented key */
    Optional<Identity> findByHash(String keyHash);

    static String sha256Hex(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
