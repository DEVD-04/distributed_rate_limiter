package com.ratelimit.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.gateway.auth.AuthException.Reason;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtValidatorTests {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final KeyPair KEYS = TokenFactory.newRsaKeyPair();
    private static final KeyPair OTHER_KEYS = TokenFactory.newRsaKeyPair();

    private final JwtValidator validator = new JwtValidator(
            Map.of("k1", (RSAPublicKey) KEYS.getPublic(), "k2", (RSAPublicKey) OTHER_KEYS.getPublic()),
            TokenFactory.ISSUER, TokenFactory.AUDIENCE, Duration.ofSeconds(30),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private TokenFactory token() {
        return new TokenFactory(NOW);
    }

    private String validToken() {
        return token().signRs256(KEYS.getPrivate());
    }

    private void assertRejected(String token, Reason expected) {
        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.reason()).isEqualTo(expected));
    }

    @Test
    void acceptsAValidToken() throws Exception {
        Identity id = validator.validate(validToken());

        assertThat(id).isEqualTo(Identity.jwt("alice", "pro"));
    }

    @Test
    void acceptsAudienceListContainingTheExpectedValue() throws Exception {
        String t = token().audience("other", TokenFactory.AUDIENCE).signRs256(KEYS.getPrivate());
        assertThat(validator.validate(t).userId()).isEqualTo("alice");
    }

    // ---- algorithm pinning ----

    @Test
    void rejectsAlgNone() {
        assertRejected(token().unsigned(), Reason.UNSUPPORTED_ALGORITHM);
    }

    @Test
    void rejectsHs256SignedWithThePublicKeyAsSecret() {
        byte[] publicKeyBytes = KEYS.getPublic().getEncoded();
        assertRejected(token().signHs256(publicKeyBytes), Reason.UNSUPPORTED_ALGORITHM);
    }

    @Test
    void rejectsHs256SignedWithAnySecret() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        assertRejected(token().signHs256(secret), Reason.UNSUPPORTED_ALGORITHM);
    }

    @Test
    void rejectsOtherRsaAlgorithmsEvenWhenCorrectlySigned() {
        assertRejected(token().signRs384(KEYS.getPrivate()), Reason.UNSUPPORTED_ALGORITHM);
    }

    // ---- keys and signatures ----

    @Test
    void rejectsUnknownKid() {
        assertRejected(token().kid("nope").signRs256(KEYS.getPrivate()), Reason.UNKNOWN_KID);
    }

    @Test
    void rejectsMissingKid() {
        assertRejected(token().kid(null).signRs256(KEYS.getPrivate()), Reason.UNKNOWN_KID);
    }

    @Test
    void rejectsSignatureFromAnotherKeyUnderAKnownKid() {
        assertRejected(token().kid("k1").signRs256(OTHER_KEYS.getPrivate()), Reason.BAD_SIGNATURE);
    }

    @Test
    void selectsTheKeyByKid() throws Exception {
        String t = token().kid("k2").signRs256(OTHER_KEYS.getPrivate());
        assertThat(validator.validate(t).userId()).isEqualTo("alice");
    }

    @Test
    void rejectsTamperedPayload() {
        String[] parts = validToken().split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"test-issuer\",\"aud\":\"gateway\",\"sub\":\"alice\",\"tier\":\"enterprise\","
                        + "\"exp\":" + NOW.plusSeconds(300).getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        assertRejected(parts[0] + "." + forged + "." + parts[2], Reason.BAD_SIGNATURE);
    }

    @Test
    void rejectsGarbage() {
        assertRejected("", Reason.MALFORMED);
        assertRejected("garbage", Reason.MALFORMED);
        assertRejected("a.b.c", Reason.MALFORMED);
    }

    // ---- time claims ----

    @Test
    void rejectsExpiredToken() {
        assertRejected(token().expiresAt(NOW.minusSeconds(31)).signRs256(KEYS.getPrivate()), Reason.EXPIRED);
    }

    @Test
    void toleratesRecentExpiryWithinLeeway() throws Exception {
        String t = token().expiresAt(NOW.minusSeconds(29)).signRs256(KEYS.getPrivate());
        assertThat(validator.validate(t).userId()).isEqualTo("alice");
    }

    @Test
    void rejectsTokenWithoutExpiry() {
        assertRejected(token().withoutExp().signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
    }

    @Test
    void rejectsTokenNotYetValid() {
        assertRejected(token().notBefore(NOW.plusSeconds(31)).signRs256(KEYS.getPrivate()), Reason.NOT_YET_VALID);
    }

    @Test
    void toleratesNotBeforeWithinLeeway() throws Exception {
        String t = token().notBefore(NOW.plusSeconds(29)).signRs256(KEYS.getPrivate());
        assertThat(validator.validate(t).userId()).isEqualTo("alice");
    }

    // ---- identity claims ----

    @Test
    void rejectsWrongIssuer() {
        assertRejected(token().issuer("evil").signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
    }

    @Test
    void rejectsWrongAudience() {
        assertRejected(token().audience("someone-else").signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
    }

    @Test
    void rejectsMissingSubject() {
        assertRejected(token().subject(null).signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
        assertRejected(token().subject("  ").signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
    }

    @Test
    void rejectsMissingOrNonStringTier() {
        assertRejected(token().tier(null).signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
        assertRejected(token().tier(42).signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
        assertRejected(token().tier("").signRs256(KEYS.getPrivate()), Reason.INVALID_CLAIMS);
    }
}
