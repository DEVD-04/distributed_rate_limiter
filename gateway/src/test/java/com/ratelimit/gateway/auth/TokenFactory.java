package com.ratelimit.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Test-only token minter (the "dev token issuer"). Keys are generated at runtime so no private
 * key is ever committed. Defaults produce a valid token for {@link #ISSUER}/{@link #AUDIENCE};
 * each setter breaks one thing so tests can aim at a single validation rule.
 */
public final class TokenFactory {

    public static final String ISSUER = "test-issuer";
    public static final String AUDIENCE = "gateway";

    private final Instant now;
    private String kid = "k1";
    private String issuer = ISSUER;
    private List<String> audience = List.of(AUDIENCE);
    private String subject = "alice";
    private Object tier = "pro";
    private Instant expiresAt;
    private Instant notBefore;
    private boolean omitExp;

    public TokenFactory(Instant now) {
        this.now = now;
        this.expiresAt = now.plus(Duration.ofMinutes(5));
    }

    public static KeyPair newRsaKeyPair() {
        return newRsaKeyPair(2048);
    }

    public static KeyPair newRsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String publicPem(KeyPair pair) {
        String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    public TokenFactory kid(String kid) { this.kid = kid; return this; }
    public TokenFactory issuer(String issuer) { this.issuer = issuer; return this; }
    public TokenFactory audience(String... audience) { this.audience = List.of(audience); return this; }
    public TokenFactory subject(String subject) { this.subject = subject; return this; }
    public TokenFactory tier(Object tier) { this.tier = tier; return this; }
    public TokenFactory expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
    public TokenFactory notBefore(Instant notBefore) { this.notBefore = notBefore; return this; }
    public TokenFactory withoutExp() { this.omitExp = true; return this; }

    private JWTClaimsSet claims() {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now));
        if (subject != null) {
            b.subject(subject);
        }
        if (tier != null) {
            b.claim("tier", tier);
        }
        if (!omitExp) {
            b.expirationTime(Date.from(expiresAt));
        }
        if (notBefore != null) {
            b.notBeforeTime(Date.from(notBefore));
        }
        return b.build();
    }

    public String signRs256(PrivateKey key) {
        return signRsa(JWSAlgorithm.RS256, key);
    }

    /** A correctly signed token with an algorithm the gateway does not accept. */
    public String signRs384(PrivateKey key) {
        return signRsa(JWSAlgorithm.RS384, key);
    }

    private String signRsa(JWSAlgorithm alg, PrivateKey key) {
        try {
            SignedJWT jwt = new SignedJWT(header(alg), claims());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HS256 token: the algorithm-confusion attack signs with the RSA public key bytes as secret. */
    public String signHs256(byte[] secret) {
        try {
            SignedJWT jwt = new SignedJWT(header(JWSAlgorithm.HS256), claims());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An unsigned token with {@code "alg":"none"}. */
    public String unsigned() {
        return new PlainJWT(claims()).serialize();
    }

    private JWSHeader header(JWSAlgorithm alg) {
        JWSHeader.Builder h = new JWSHeader.Builder(alg).type(JOSEObjectType.JWT);
        if (kid != null) {
            h.keyID(kid);
        }
        return h.build();
    }
}
