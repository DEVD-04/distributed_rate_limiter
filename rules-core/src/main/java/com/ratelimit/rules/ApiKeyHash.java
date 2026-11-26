package com.ratelimit.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How API keys are stored and looked up: as the lower-case hex SHA-256 of the key, never the key
 * itself. The policy service writes these hashes and the gateway looks them up, so both use this.
 */
public final class ApiKeyHash {

    private ApiKeyHash() {
    }

    public static String of(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
