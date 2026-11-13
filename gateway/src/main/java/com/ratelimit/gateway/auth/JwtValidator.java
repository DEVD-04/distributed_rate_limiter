package com.ratelimit.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Validates RS256 access tokens.
 *
 * <ul>
 *   <li>The algorithm is pinned to RS256 <em>before</em> any key is looked up, so {@code none},
 *       HS256 (the classic "public key used as HMAC secret" attack) and every other algorithm are
 *       rejected outright.
 *   <li>The verification key is chosen only by {@code kid} from the configured set. Header fields
 *       that point at keys ({@code jku}, {@code x5u}, {@code jwk}) are ignored, never fetched.
 *   <li>{@code exp}, {@code nbf}, {@code iss}, {@code aud} are checked with a configurable leeway;
 *       {@code sub} (user id) and {@code tier} must be present.
 * </ul>
 */
public class JwtValidator {

    static final String TIER_CLAIM = "tier";

    private final Map<String, RSAPublicKey> keysByKid;
    private final String issuer;
    private final String audience;
    private final Duration leeway;
    private final Clock clock;

    public JwtValidator(Map<String, RSAPublicKey> keysByKid, String issuer, String audience,
            Duration leeway, Clock clock) {
        this.keysByKid = Map.copyOf(keysByKid);
        this.issuer = issuer;
        this.audience = audience;
        this.leeway = leeway;
        this.clock = clock;
    }

    public Identity validate(String token) throws AuthException {
        SignedJWT jwt = parseSigned(token);

        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new AuthException(AuthException.Reason.UNSUPPORTED_ALGORITHM,
                    "alg " + jwt.getHeader().getAlgorithm());
        }
        String kid = jwt.getHeader().getKeyID();
        RSAPublicKey key = kid == null ? null : keysByKid.get(kid);
        if (key == null) {
            throw new AuthException(AuthException.Reason.UNKNOWN_KID, "kid " + kid);
        }
        verifySignature(jwt, key);

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new AuthException(AuthException.Reason.MALFORMED, "claims: " + e.getMessage());
        }
        checkTimes(claims);
        checkIssuerAndAudience(claims);

        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            throw new AuthException(AuthException.Reason.INVALID_CLAIMS, "missing sub");
        }
        String tier = stringClaim(claims, TIER_CLAIM);
        if (tier == null || tier.isBlank()) {
            throw new AuthException(AuthException.Reason.INVALID_CLAIMS, "missing tier");
        }
        return Identity.jwt(userId, tier);
    }

    private static SignedJWT parseSigned(String token) throws AuthException {
        try {
            var parsed = JWTParser.parse(token);
            if (parsed instanceof SignedJWT signed) {
                return signed;
            }
            // PlainJWT: alg "none"
            throw new AuthException(AuthException.Reason.UNSUPPORTED_ALGORITHM, "unsigned token");
        } catch (ParseException e) {
            throw new AuthException(AuthException.Reason.MALFORMED, "unparseable token");
        }
    }

    private static void verifySignature(SignedJWT jwt, RSAPublicKey key) throws AuthException {
        try {
            if (!jwt.verify(new RSASSAVerifier(key))) {
                throw new AuthException(AuthException.Reason.BAD_SIGNATURE, "signature mismatch");
            }
        } catch (JOSEException e) {
            throw new AuthException(AuthException.Reason.BAD_SIGNATURE, "verification failed");
        }
    }

    private void checkTimes(JWTClaimsSet claims) throws AuthException {
        Instant now = clock.instant();
        Date exp = claims.getExpirationTime();
        if (exp == null) {
            throw new AuthException(AuthException.Reason.INVALID_CLAIMS, "missing exp");
        }
        if (now.isAfter(exp.toInstant().plus(leeway))) {
            throw new AuthException(AuthException.Reason.EXPIRED, "exp " + exp.toInstant());
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && now.isBefore(nbf.toInstant().minus(leeway))) {
            throw new AuthException(AuthException.Reason.NOT_YET_VALID, "nbf " + nbf.toInstant());
        }
    }

    private void checkIssuerAndAudience(JWTClaimsSet claims) throws AuthException {
        if (!issuer.equals(claims.getIssuer())) {
            throw new AuthException(AuthException.Reason.INVALID_CLAIMS, "iss " + claims.getIssuer());
        }
        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(audience)) {
            throw new AuthException(AuthException.Reason.INVALID_CLAIMS, "aud " + aud);
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String s ? s : null;
    }
}
