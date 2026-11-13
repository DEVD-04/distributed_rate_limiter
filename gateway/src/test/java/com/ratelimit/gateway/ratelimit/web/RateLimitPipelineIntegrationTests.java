package com.ratelimit.gateway.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ratelimit.gateway.auth.ApiKeyStore;
import com.ratelimit.gateway.auth.TokenFactory;
import java.security.KeyPair;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The whole filter chain: request id, client IP, IP limit, authentication, user/key limit.
 * Every limit here refills at 0.01 token/s, so nothing refills during a test. Each test uses its own
 * user, key and client IP so buckets never interfere with one another.
 */
@SpringBootTest(properties = {
        "gateway.trusted-proxies=172.18.0.0/16",
        "gateway.auth.public-paths=/test/open/**",

        "gateway.ratelimit.rules[0].id=1",
        "gateway.ratelimit.rules[0].name=ip-strict",
        "gateway.ratelimit.rules[0].limit-by=IP",
        "gateway.ratelimit.rules[0].endpoint-pattern=/test/ip/**",
        "gateway.ratelimit.rules[0].algorithm=TOKEN_BUCKET",
        "gateway.ratelimit.rules[0].params.capacity=3",
        "gateway.ratelimit.rules[0].params.refill-per-sec=0.01",

        "gateway.ratelimit.rules[1].id=2",
        "gateway.ratelimit.rules[1].name=user-free",
        "gateway.ratelimit.rules[1].limit-by=USER",
        "gateway.ratelimit.rules[1].match-tier=free",
        "gateway.ratelimit.rules[1].endpoint-pattern=/test/user/**",
        "gateway.ratelimit.rules[1].algorithm=TOKEN_BUCKET",
        "gateway.ratelimit.rules[1].params.capacity=2",
        "gateway.ratelimit.rules[1].params.refill-per-sec=0.01",

        "gateway.ratelimit.rules[2].id=3",
        "gateway.ratelimit.rules[2].name=user-pro",
        "gateway.ratelimit.rules[2].limit-by=USER",
        "gateway.ratelimit.rules[2].match-tier=pro",
        "gateway.ratelimit.rules[2].endpoint-pattern=/test/user/**",
        "gateway.ratelimit.rules[2].algorithm=TOKEN_BUCKET",
        "gateway.ratelimit.rules[2].params.capacity=5",
        "gateway.ratelimit.rules[2].params.refill-per-sec=0.01",

        "gateway.ratelimit.rules[3].id=4",
        "gateway.ratelimit.rules[3].name=key-strict",
        "gateway.ratelimit.rules[3].limit-by=API_KEY",
        "gateway.ratelimit.rules[3].endpoint-pattern=/test/keyed/**",
        "gateway.ratelimit.rules[3].algorithm=TOKEN_BUCKET",
        "gateway.ratelimit.rules[3].params.capacity=1",
        "gateway.ratelimit.rules[3].params.refill-per-sec=0.01",
})
@AutoConfigureMockMvc
class RateLimitPipelineIntegrationTests {

    private static final KeyPair KEYS = TokenFactory.newRsaKeyPair();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Raw API keys known to the gateway; each test that needs one uses its own. */
    private static final String[] API_KEYS = {"key-one", "key-two", "key-three"};

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry registry) {
        registry.add("gateway.auth.jwt.issuer", () -> TokenFactory.ISSUER);
        registry.add("gateway.auth.jwt.audience", () -> TokenFactory.AUDIENCE);
        registry.add("gateway.auth.jwt.public-keys.k1", () -> TokenFactory.publicPem(KEYS));
        for (int i = 0; i < API_KEYS.length; i++) {
            String prefix = "gateway.auth.api-keys[" + i + "].";
            String rawKey = API_KEYS[i];
            String owner = "key-owner-" + i;
            registry.add(prefix + "key-hash", () -> ApiKeyStore.sha256Hex(rawKey));
            registry.add(prefix + "user-id", () -> owner);
            registry.add(prefix + "tier", () -> "free");
        }
    }

    @Autowired
    MockMvc mvc;

    private static String uniqueUser() {
        return "user-" + SEQUENCE.incrementAndGet();
    }

    private static String uniqueIp() {
        return "198.51.100." + (SEQUENCE.incrementAndGet() % 250 + 1);
    }

    private static RequestPostProcessor peer(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String jwt(String user, String tier) {
        return "Bearer " + new TokenFactory(Instant.now()).subject(user).tier(tier).signRs256(KEYS.getPrivate());
    }

    private ResultActions userCall(String path, String bearer, String ip) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer).with(peer(ip)));
    }

    // ---- allowed requests ----

    @Test
    void allowedResponsesCarryTheRateLimitHeaders() throws Exception {
        userCall("/test/user/a", jwt(uniqueUser(), "free"), uniqueIp())
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "1"))
                .andExpect(header().string("X-RateLimit-Reset", "100")) // one spent token at 0.01/s
                .andExpect(header().string("X-RateLimit-Policy", "user-free"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void remainingCountsDownAcrossRequests() throws Exception {
        String bearer = jwt(uniqueUser(), "pro");
        String ip = uniqueIp();
        for (int remaining = 4; remaining >= 0; remaining--) {
            userCall("/test/user/a", bearer, ip)
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(remaining)));
        }
    }

    // ---- the 429 contract ----

    @Test
    void exceedingTheLimitReturns429WithTheDocumentedBodyAndHeaders() throws Exception {
        String bearer = jwt(uniqueUser(), "free");
        String ip = uniqueIp();
        userCall("/test/user/limited", bearer, ip).andExpect(status().isOk());
        userCall("/test/user/limited", bearer, ip).andExpect(status().isOk());

        MvcResult rejected = userCall("/test/user/limited", bearer, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(header().string("X-RateLimit-Policy", "user-free"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error").value("rate_limit_exceeded"))
                .andExpect(jsonPath("$.policy").value("user-free"))
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(99L, 100L); // one token at 0.01/s
        String body = rejected.getResponse().getContentAsString();
        assertThat(body).contains("\"retry_after_seconds\":" + retryAfter);
        assertThat(body).contains("\"message\":\"Too many requests. Retry after " + retryAfter + " seconds.\"");
        // Field order follows the contract.
        assertThat(body.indexOf("\"error\"")).isLessThan(body.indexOf("\"message\""));
        assertThat(body.indexOf("\"message\"")).isLessThan(body.indexOf("\"retry_after_seconds\""));
        assertThat(body.indexOf("\"retry_after_seconds\"")).isLessThan(body.indexOf("\"policy\""));
    }

    @Test
    void aRejectedRequestNeverReachesTheHandler() throws Exception {
        String bearer = jwt(uniqueUser(), "free");
        String ip = uniqueIp();
        String path = "/test/user/handler-count";
        for (int i = 0; i < 5; i++) {
            userCall(path, bearer, ip);
        }

        assertThat(CountingController.hits(path)).isEqualTo(2); // the bucket holds 2; the other 3 were stopped
    }

    // ---- who is limited, and by which rule ----

    @Test
    void theCallersTierSelectsTheRule() throws Exception {
        String bearer = jwt(uniqueUser(), "pro");
        String ip = uniqueIp();
        for (int i = 0; i < 5; i++) {
            userCall("/test/user/a", bearer, ip).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Policy", "user-pro"));
        }
        userCall("/test/user/a", bearer, ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.policy").value("user-pro"));
    }

    @Test
    void usersHaveIndependentLimits() throws Exception {
        String ip = uniqueIp();
        String first = jwt(uniqueUser(), "free");
        String second = jwt(uniqueUser(), "free");
        for (int i = 0; i < 3; i++) {
            userCall("/test/user/a", first, ip);
        }

        userCall("/test/user/a", first, ip).andExpect(status().isTooManyRequests());
        userCall("/test/user/a", second, ip).andExpect(status().isOk());
    }

    @Test
    void anApiKeyRuleLimitsPerKeyAndDoesNotApplyToJwtCallers() throws Exception {
        String ip = uniqueIp();
        mvc.perform(get("/test/keyed/a").header("X-API-Key", API_KEYS[0]).with(peer(ip)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Policy", "key-strict"))
                .andExpect(header().string("X-RateLimit-Limit", "1"));
        mvc.perform(get("/test/keyed/a").header("X-API-Key", API_KEYS[0]).with(peer(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.policy").value("key-strict"));

        // A JWT caller has no API key, so the API_KEY rule is not eligible: the built-in user default applies.
        userCall("/test/keyed/a", jwt(uniqueUser(), "free"), ip)
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Policy", "default-user"));
    }

    @Test
    void differentApiKeysHaveSeparateBuckets() throws Exception {
        String ip = uniqueIp();
        mvc.perform(get("/test/keyed/b").header("X-API-Key", API_KEYS[1]).with(peer(ip))).andExpect(status().isOk());
        mvc.perform(get("/test/keyed/b").header("X-API-Key", API_KEYS[1]).with(peer(ip))).andExpect(status().isTooManyRequests());
        mvc.perform(get("/test/keyed/b").header("X-API-Key", API_KEYS[2]).with(peer(ip))).andExpect(status().isOk());
    }

    // ---- the IP stage ----

    @Test
    void theIpStageRejectsBeforeAuthenticationRuns() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/test/ip/a").with(peer(ip))).andExpect(status().isUnauthorized());
        }

        // The 4th unauthenticated request is stopped by the IP limit, not answered with 401.
        mvc.perform(get("/test/ip/a").with(peer(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.policy").value("ip-strict"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void validCredentialsDoNotBypassTheIpLimit() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            userCall("/test/ip/a", jwt(uniqueUser(), "free"), ip).andExpect(status().isOk());
        }

        userCall("/test/ip/a", jwt(uniqueUser(), "free"), ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void behindATrustedProxyEachRealClientHasItsOwnIpBucket() throws Exception {
        String clientA = uniqueIp();
        String clientB = uniqueIp();
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/test/ip/a").with(peer("172.18.0.2")).header("X-Forwarded-For", clientA))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/test/ip/a").with(peer("172.18.0.2")).header("X-Forwarded-For", clientA))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/test/ip/a").with(peer("172.18.0.2")).header("X-Forwarded-For", clientB))
                .andExpect(status().isUnauthorized()); // client B still has its own tokens
    }

    @Test
    void aClientCannotEscapeTheIpLimitByForgingForwardedFor() throws Exception {
        String ip = uniqueIp(); // not a trusted proxy, so its X-Forwarded-For is ignored
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/test/ip/a").with(peer(ip)).header("X-Forwarded-For", "203.0.113." + i))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/test/ip/a").with(peer(ip)).header("X-Forwarded-For", "203.0.113.200"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void publicPathsSkipTheIdentityStageAndAreLimitedByIpOnly() throws Exception {
        mvc.perform(get("/test/open/a").with(peer(uniqueIp())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Policy", "default-ip"));
    }
}
