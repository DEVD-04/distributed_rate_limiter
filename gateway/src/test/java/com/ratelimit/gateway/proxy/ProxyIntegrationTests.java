package com.ratelimit.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.gateway.auth.TokenFactory;
import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The gateway proxying to real HTTP servers (see {@link StubUpstream}) over its real HTTP client.
 * Each scenario that changes pool health has its own service, so tests cannot affect one another.
 */
@SpringBootTest(properties = {
        "gateway.proxy.connect-timeout=500ms",
        "gateway.proxy.read-timeout=500ms",
        "gateway.proxy.ejection.failure-threshold=3",
        "gateway.proxy.ejection.cooldown=60s",
})
@AutoConfigureMockMvc
class ProxyIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair KEYS = TokenFactory.newRsaKeyPair();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final StubUpstream ALPHA_A;
    private static final StubUpstream ALPHA_B;
    private static final StubUpstream IDENT;
    private static final StubUpstream GOOD;
    private static final StubUpstream GOOD_FOR_BODY;
    private static final StubUpstream STATUS;
    private static final StubUpstream SLOW;
    private static final String DEAD_FLAKY;
    private static final String DEAD_FLAKY_BODY;
    private static final String DEAD_ONLY;

    static {
        try {
            ALPHA_A = new StubUpstream("alpha-a");
            ALPHA_B = new StubUpstream("alpha-b");
            IDENT = new StubUpstream("ident");
            GOOD = new StubUpstream("good");
            GOOD_FOR_BODY = new StubUpstream("good-for-body");
            STATUS = new StubUpstream("status");
            SLOW = new StubUpstream("slow");
            DEAD_FLAKY = StubUpstream.deadUrl();
            DEAD_FLAKY_BODY = StubUpstream.deadUrl();
            DEAD_ONLY = StubUpstream.deadUrl();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopStubs() {
        for (StubUpstream stub : List.of(ALPHA_A, ALPHA_B, IDENT, GOOD, GOOD_FOR_BODY, STATUS, SLOW)) {
            stub.close();
        }
    }

    private static void route(DynamicPropertyRegistry r, int index, String pattern, String service, String... instances) {
        String prefix = "gateway.proxy.routes[" + index + "].";
        r.add(prefix + "pattern", () -> pattern);
        r.add(prefix + "service", () -> service);
        for (int i = 0; i < instances.length; i++) {
            String url = instances[i];
            r.add(prefix + "instances[" + i + "]", () -> url);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("gateway.auth.jwt.issuer", () -> TokenFactory.ISSUER);
        r.add("gateway.auth.jwt.audience", () -> TokenFactory.AUDIENCE);
        r.add("gateway.auth.jwt.public-keys.k1", () -> TokenFactory.publicPem(KEYS));

        route(r, 0, "/up/a/**", "alpha", ALPHA_A.url(), ALPHA_B.url());
        route(r, 1, "/up/b/**", "flaky", DEAD_FLAKY, GOOD.url());
        route(r, 2, "/up/c/**", "slow", SLOW.url());
        route(r, 3, "/up/d/**", "ident", IDENT.url());
        route(r, 4, "/up/e/**", "down", DEAD_ONLY);
        route(r, 5, "/up/f/**", "status503", STATUS.url());
        route(r, 6, "/up/g/**", "status500", STATUS.url());
        route(r, 7, "/up/h/**", "flaky-body", DEAD_FLAKY_BODY, GOOD_FOR_BODY.url());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RouteTable routes;

    // ---- helpers ----

    private static String bearer() {
        String user = "user-" + SEQUENCE.incrementAndGet();
        return "Bearer " + new TokenFactory(Instant.now()).subject(user).tier("pro").signRs256(KEYS.getPrivate());
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String uniqueIp() {
        return "198.51.100." + (SEQUENCE.incrementAndGet() % 250 + 1);
    }

    private ResultActions authed(String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer()).with(from(uniqueIp())));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String headerOf(StubUpstream stub, String name) {
        List<String> values = stub.lastHeaders().get(name);
        return values == null ? null : String.join(",", values);
    }

    // ---- routing and pass-through ----

    @Test
    void aRequestIsRoutedByPathAndTheResponseIsPassedBack() throws Exception {
        // A URI object, not a String: MockMvc would treat a String as a template and encode the '%' again.
        MvcResult result = mvc.perform(get(URI.create("/up/a/some/path?q=1&z=%20a"))
                        .header("Authorization", bearer()).with(from(uniqueIp())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(header().string("X-Custom", "yes"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andReturn();

        assertThat(body(result).get("uri").asText()).isEqualTo("/up/a/some/path?q=1&z=%20a");
    }

    @Test
    void instancesShareTheLoadRoundRobin() throws Exception {
        List<String> served = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            served.add(authed("/up/a/x").andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Instance"));
        }

        assertThat(served).containsOnly("alpha-a", "alpha-b");
        assertThat(served).filteredOn("alpha-a"::equals).hasSize(3);
        for (int i = 1; i < served.size(); i++) {
            assertThat(served.get(i)).isNotEqualTo(served.get(i - 1));
        }
    }

    @Test
    void unroutedPathsAreNotFoundWithAJsonBody() throws Exception {
        authed("/nothing/here")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.request_id").exists());
    }

    @Test
    void methodsAndBodiesAreForwarded() throws Exception {
        mvc.perform(delete("/up/a/x/1").header("Authorization", bearer()).with(from(uniqueIp())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.method").value("DELETE"));

        byte[] payload = "{\"hello\":\"world\"}".getBytes();
        MvcResult posted = mvc.perform(post("/up/a/x").header("Authorization", bearer()).with(from(uniqueIp()))
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(posted).get("method").asText()).isEqualTo("POST");
        assertThat(body(posted).get("bodyLength").asInt()).isEqualTo(payload.length);
        assertThat(body(posted).get("bodySha256").asText()).isEqualTo(StubUpstream.sha256(payload));
    }

    @Test
    void aLargeRequestBodyArrivesIntact() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        new Random(7).nextBytes(payload);

        MvcResult result = mvc.perform(post("/up/a/upload").header("Authorization", bearer()).with(from(uniqueIp()))
                        .contentType("application/octet-stream").content(payload))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(result).get("bodyLength").asInt()).isEqualTo(payload.length);
        assertThat(body(result).get("bodySha256").asText()).isEqualTo(StubUpstream.sha256(payload));
    }

    @Test
    void aLargeResponseBodyIsStreamedBackIntact() throws Exception {
        MvcResult result = authed("/up/a/big").andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(StubUpstream.bigBody());
    }

    @Test
    void headRequestsGetHeadersAndNoBody() throws Exception {
        MvcResult result = mvc.perform(head("/up/a/x").header("Authorization", bearer()).with(from(uniqueIp())))
                .andExpect(status().isOk()).andExpect(header().string("X-Custom", "yes")).andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    void traceIsNotAllowed() throws Exception {
        mvc.perform(request(HttpMethod.TRACE, "/up/a/x").header("Authorization", bearer()).with(from(uniqueIp())))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"));
    }

    // ---- headers ----

    @Test
    void theGatewayDecidesTheIdentityAndForwardingHeadersTheUpstreamSees() throws Exception {
        String user = "alice-" + SEQUENCE.incrementAndGet();
        String jwt = new TokenFactory(Instant.now()).subject(user).tier("pro").signRs256(KEYS.getPrivate());

        MvcResult result = mvc.perform(get("/up/d/x").with(from("203.0.113.55"))
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-Request-Id", "client-trace-1")
                        .header("X-User-Id", "admin")                       // forged
                        .header("X-Tier", "enterprise")                     // forged
                        .header("X-Forwarded-For", "6.6.6.6")               // forged
                        .header("Forwarded", "for=6.6.6.6")                 // forged
                        .header("X-Real-IP", "6.6.6.6")                     // forged
                        .header("Connection", "keep-alive, X-Hop")
                        .header("X-Hop", "private")
                        .header("Keep-Alive", "timeout=5")
                        .header("Accept", "application/json")
                        .header("X-Custom-Request", "kept"))
                .andExpect(status().isOk()).andReturn();

        assertThat(headerOf(IDENT, "x-user-id")).isEqualTo(user);
        assertThat(headerOf(IDENT, "x-tier")).isEqualTo("pro");
        assertThat(headerOf(IDENT, "x-request-id")).isEqualTo("client-trace-1");
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("client-trace-1");
        assertThat(headerOf(IDENT, "x-forwarded-for")).isEqualTo("203.0.113.55");
        assertThat(IDENT.lastHeaders()).doesNotContainKeys("forwarded", "x-real-ip", "authorization", "x-api-key",
                "x-hop", "keep-alive");
        assertThat(headerOf(IDENT, "accept")).isEqualTo("application/json");
        assertThat(headerOf(IDENT, "x-custom-request")).isEqualTo("kept");
    }

    @Test
    void theGatewayKeepsOwnershipOfRateLimitAndRequestIdHeaders() throws Exception {
        MvcResult result = authed("/up/a/x").andExpect(status().isOk()).andReturn();

        // The stub answered with X-RateLimit-Limit: 999 and X-Request-Id: upstream-id.
        assertThat(result.getResponse().getHeader("X-RateLimit-Limit")).isNotEqualTo("999").isNotNull();
        assertThat(result.getResponse().getHeaders("X-RateLimit-Limit")).hasSize(1);
        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotEqualTo("upstream-id").isNotNull();
        assertThat(result.getResponse().getHeaders("X-Request-Id")).hasSize(1);
    }

    // ---- path safety ----

    @Test
    void nonCanonicalPathsAreRefusedBeforeReachingAnyUpstream() throws Exception {
        int before = ALPHA_A.requests() + ALPHA_B.requests() + IDENT.requests() + STATUS.requests();

        for (String raw : List.of("/up/a/../d/x", "/up/a/./x", "/up/a//x", "/up/a/%2e%2e/d/x", "/up/a/%2E%2E/x",
                "/up/a%2fx", "/up/a/x;jsessionid=1")) {
            mvc.perform(get(URI.create(raw)).header("Authorization", bearer()).with(from(uniqueIp())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }

        assertThat(ALPHA_A.requests() + ALPHA_B.requests() + IDENT.requests() + STATUS.requests()).isEqualTo(before);
    }

    // ---- upstream failures ----

    @Test
    void anUpstreamThatDoesNotAnswerInTimeIsAGatewayTimeout() throws Exception {
        authed("/up/c/slow")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("gateway_timeout"))
                .andExpect(jsonPath("$.request_id").exists());
    }

    @Test
    void anUpstreamThatRefusesConnectionsIsABadGateway() throws Exception {
        authed("/up/e/x")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("bad_gateway"));
    }

    @Test
    void aBodylessRequestFailsOverToAnotherInstanceAndTheDeadOneIsEventuallyEjected() throws Exception {
        for (int i = 0; i < 8; i++) {
            authed("/up/b/x").andExpect(status().isOk()).andExpect(header().string("X-Instance", "good"));
        }

        UpstreamPool pool = routes.pool("flaky").orElseThrow();
        UpstreamPool.Upstream dead = pool.instances().stream()
                .filter(u -> u.base().toString().equals(DEAD_FLAKY)).findFirst().orElseThrow();
        assertThat(pool.isEjected(dead)).isTrue();
    }

    @Test
    void aRequestWithABodyIsNotRetriedOnAnotherInstance() throws Exception {
        // The first pick of a fresh pool is the dead instance (index 0).
        mvc.perform(post("/up/h/x").header("Authorization", bearer()).with(from(uniqueIp()))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadGateway());

        assertThat(GOOD_FOR_BODY.requests()).isZero();
    }

    @Test
    void upstream503sArePassedThroughAndEjectTheInstanceAfterTheThreshold() throws Exception {
        for (int i = 0; i < 3; i++) {
            authed("/up/f/status/503").andExpect(status().isServiceUnavailable());
        }

        UpstreamPool pool = routes.pool("status503").orElseThrow();
        assertThat(pool.isEjected(pool.instances().get(0))).isTrue();
        // With the only instance ejected the pool still probes it, so the service recovers when it can.
        authed("/up/f/x").andExpect(status().isOk());
    }

    @Test
    void upstream500sArePassedThroughButDoNotEjectTheInstance() throws Exception {
        for (int i = 0; i < 6; i++) {
            authed("/up/g/status/500").andExpect(status().isInternalServerError());
        }

        UpstreamPool pool = routes.pool("status500").orElseThrow();
        assertThat(pool.isEjected(pool.instances().get(0))).isFalse();
    }
}
