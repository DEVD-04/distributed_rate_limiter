package com.ratelimit.gateway.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/** Reads RSA public keys in PEM ({@code BEGIN PUBLIC KEY}, X.509 SubjectPublicKeyInfo) form. */
final class PublicKeyLoader {

    static final int MIN_RSA_BITS = 2048;

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    private PublicKeyLoader() {
    }

    /** @param source an inline PEM block, or a resource location such as {@code file:/keys/a.pem} */
    static RSAPublicKey load(String source) {
        String pem = source.trim().startsWith("-----BEGIN") ? source : read(source);
        return parse(pem);
    }

    private static String read(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read public key from '" + location + "'", e);
        }
    }

    static RSAPublicKey parse(String pem) {
        String trimmed = pem.trim();
        if (!trimmed.startsWith(BEGIN) || !trimmed.endsWith(END)) {
            throw new IllegalArgumentException("Expected a PEM block of type PUBLIC KEY");
        }
        String base64 = trimmed.substring(BEGIN.length(), trimmed.length() - END.length())
                .replaceAll("\\s", "");
        RSAPublicKey rsa;
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            rsa = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException("Not a valid RSA public key", e);
        }
        if (rsa.getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalArgumentException(
                    "RSA key too small: " + rsa.getModulus().bitLength() + " bits, need " + MIN_RSA_BITS);
        }
        return rsa;
    }
}
