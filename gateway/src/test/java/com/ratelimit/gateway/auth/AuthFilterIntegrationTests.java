package com.ratelimit.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The real filter chain with RS256 keys and API keys configured, as in a deployment. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFilterIntegrationTests {

    private static final KeyPair KEYS = TokenFactory.newRsaKeyPair();
    private static final KeyPair ATTACKER_KEYS = TokenFactory.newRsaKeyPair();

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.jwt.issuer", () -> TokenFactory.ISSUER);
        registry.add("gateway.auth.jwt.audience", () -> TokenFactory.AUDIENCE);
        registry.add("gateway.auth.jwt.public-keys.k1", () -> TokenFactory.publicPem(KEYS));
        registry.add("gateway.auth.api-keys[0].key-hash", () -> ApiKeyStore.sha256Hex("good-key"));
        registry.add("gateway.auth.api-keys[0].user-id", () -> "bob");
        registry.add("gateway.auth.api-keys[0].tier", () -> "free");
        registry.add("gateway.auth.api-keys[1].key-hash", () -> ApiKeyStore.sha256Hex("revoked-key"));
        registry.add("gateway.auth.api-keys[1].user-id", () -> "carol");
        registry.add("gateway.auth.api-keys[1].tier", () -> "pro");
        registry.add("gateway.auth.api-keys[1].active", () -> "false");
        registry.add("gateway.auth.public-paths[0]", () -> "/test/public");
    }

    @Autowired
    MockMvc mvc;

    private static TokenFactory token() {
        return new TokenFactory(Instant.now());
    }

    private static String bearer(String jwt) {
        return "Bearer " + jwt;
    }

    @Test
    void validJwtReachesTheHandlerWithItsIdentity() throws Exception {
        String jwt = token().signRs256(KEYS.getPrivate());

        mvc.perform(get("/test/whoami").header("Authorization", bearer(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.tier").value("pro"))
                .andExpect(jsonPath("$.method").value("JWT"));
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        String jwt = token().signRs256(KEYS.getPrivate());

        mvc.perform(get("/test/whoami").header("Authorization", "bearer " + jwt))
                .andExpect(status().isOk());
    }

    @Test
    void validApiKeyReachesTheHandlerWithItsIdentity() throws Exception {
        mvc.perform(get("/test/whoami").header("X-API-Key", "good-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("bob"))
                .andExpect(jsonPath("$.tier").value("free"))
                .andExpect(jsonPath("$.method").value("API_KEY"));
    }

    @Test
    void noCredentialsIsUnauthorizedWithAGenericBody() throws Exception {
        MvcResult result = mvc.perform(get("/test/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andReturn();

        String requestIdHeader = result.getResponse().getHeader("X-Request-Id");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"request_id\":\"" + requestIdHeader + "\"");
    }

    @Test
    void everyFailureLooksTheSameToTheClient() throws Exception {
        String expiredJwt = token().expiresAt(Instant.now().minus(Duration.ofHours(1))).signRs256(KEYS.getPrivate());
        String wrongKid = token().kid("nope").signRs256(KEYS.getPrivate());
        String badSignature = token().signRs256(ATTACKER_KEYS.getPrivate());

        String[] bodies = new String[4];
        String[] authHeaders = {bearer(expiredJwt), bearer(wrongKid), bearer(badSignature), "Bearer junk"};
        for (int i = 0; i < authHeaders.length; i++) {
            bodies[i] = mvc.perform(get("/test/whoami").header("Authorization", authHeaders[i]))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("\"request_id\":\"[^\"]*\"", "");
        }
        assertThat(bodies).containsOnly(bodies[0]);
        // Only the fixed generic message: no per-failure reason, kid or claim values.
        assertThat(bodies[0]).isEqualTo(
                "{\"error\":\"unauthorized\",\"message\":\"Missing, invalid or expired credentials.\",}");
    }

    @Test
    void jwtAttacksAreRejected() throws Exception {
        byte[] publicBytes = KEYS.getPublic().getEncoded();
        String[] attacks = {
                token().unsigned(),                                   // alg=none
                token().signHs256(publicBytes),                       // HS256 with public key as secret
                token().signRs384(KEYS.getPrivate()),                 // wrong RSA variant
                token().kid("wrong-kid").signRs256(KEYS.getPrivate()),
                token().signRs256(ATTACKER_KEYS.getPrivate()),        // wrong signature under right kid
                token().expiresAt(Instant.now().minusSeconds(3600)).signRs256(KEYS.getPrivate()),
        };
        for (String attack : attacks) {
            mvc.perform(get("/test/whoami").header("Authorization", bearer(attack)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unknownAndRevokedApiKeysAreRejected() throws Exception {
        mvc.perform(get("/test/whoami").header("X-API-Key", "made-up")).andExpect(status().isUnauthorized());
        mvc.perform(get("/test/whoami").header("X-API-Key", "revoked-key")).andExpect(status().isUnauthorized());
        mvc.perform(get("/test/whoami").header("X-API-Key", "  ")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonBearerAuthorizationSchemeIsRejected() throws Exception {
        mvc.perform(get("/test/whoami").header("Authorization", "Basic YWxpY2U6c2VjcmV0"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBadBearerTokenIsNotRescuedByAValidApiKey() throws Exception {
        mvc.perform(get("/test/whoami")
                        .header("Authorization", "Bearer junk")
                        .header("X-API-Key", "good-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientCannotInjectIdentityThroughForwardedHeaders() throws Exception {
        mvc.perform(get("/test/whoami")
                        .header("X-User-Id", "admin")
                        .header("X-Tier", "enterprise"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPathsSkipAuthentication() throws Exception {
        mvc.perform(get("/test/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }
}
