package com.ratelimit.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.rules.ApiKeyHash;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The admin API against a real PostgreSQL. Changes really commit (so that "a refused change leaves
 * nothing behind" is meaningful), and every test starts from a freshly migrated database.
 */
@SpringBootTest(properties = {
        "policy.admin-token=" + AdminApiTests.TOKEN,
        "spring.flyway.clean-disabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestSupport.class)
class AdminApiTests {

    static final String TOKEN = "admin-api-test-token-0123456789";

    @Autowired MockMvc mvc;
    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void freshDatabase() {
        flyway.clean();
        flyway.migrate();
    }

    // ---- helpers ----

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private long configVersion() {
        return jdbc.sql("SELECT version FROM config_version").query(Long.class).single();
    }

    private int ruleCount() {
        return jdbc.sql("SELECT count(*) FROM ratelimit_rules").query(Integer.class).single();
    }

    private long idOf(String ruleName) {
        return jdbc.sql("SELECT rule_id FROM ratelimit_rules WHERE name = :n AND is_active").param("n", ruleName)
                .query(Long.class).single();
    }

    /** A valid token bucket rule for the given path, as JSON. */
    private static String tokenBucket(String name, String path) {
        return """
                {"name": "%s", "limitBy": "USER", "endpointPattern": "%s", "algorithm": "TOKEN_BUCKET",
                 "params": {"capacity": 5, "refill_per_sec": 1}}""".formatted(name, path);
    }

    private static String rule(String fields) {
        return "{\"name\": \"r\", \"limitBy\": \"USER\", \"endpointPattern\": \"/x/**\", " + fields + "}";
    }

    /** Asserts a change is refused and saves nothing. */
    private void assertRefused(MockHttpServletRequestBuilder request, int expectedStatus, String messagePart) throws Exception {
        long versionBefore = configVersion();
        String rulesBefore = jdbc.sql("SELECT string_agg(r::text, '|' ORDER BY rule_id) FROM ratelimit_rules r")
                .query(String.class).single();

        var response = admin(request).andReturn().getResponse();

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        assertThat(response.getContentAsString()).contains(messagePart);
        assertThat(configVersion()).as("version must not move").isEqualTo(versionBefore);
        assertThat(jdbc.sql("SELECT string_agg(r::text, '|' ORDER BY rule_id) FROM ratelimit_rules r")
                .query(String.class).single()).as("rows must not change").isEqualTo(rulesBefore);
    }

    // ---- authentication ----

    @Test
    void withoutTheTokenEverythingIsRefused() throws Exception {
        mvc.perform(get("/admin/rules")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(get("/admin/rules").header("X-Admin-Token", "wrong-token-wrong-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/rules").header("X-Admin-Token", TOKEN.substring(0, TOKEN.length() - 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/no/such/path")).andExpect(status().isUnauthorized()); // not even a 404 without it
    }

    @Test
    void anUnauthorizedChangeChangesNothing() throws Exception {
        mvc.perform(delete("/admin/rules/" + idOf("free-images"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/api-keys").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": \"mallory\", \"tier\": \"enterprise\"}")).andExpect(status().isUnauthorized());

        assertThat(configVersion()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM api_keys").query(Integer.class).single()).isZero();
    }

    // ---- reading ----

    @Test
    void listsAndFetchesRules() throws Exception {
        admin(get("/admin/rules")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        admin(get("/admin/rules/" + idOf("pro-images"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("pro-images"))
                .andExpect(jsonPath("$.matchTier").value("pro"))
                .andExpect(jsonPath("$.params.capacity").value(100))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isString());
        admin(get("/admin/version")).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anUnknownRuleIsNotFoundAndABadIdIsABadRequest() throws Exception {
        admin(get("/admin/rules/9999")).andExpect(status().isNotFound());
        admin(get("/admin/rules/abc")).andExpect(status().isBadRequest());
    }

    // ---- creating ----

    @Test
    void creatingARuleSavesItAndBumpsTheVersion() throws Exception {
        admin(post("/admin/rules").content("""
                {"name": "search", "limitBy": "USER", "matchTier": "free", "endpointPattern": "/api/search/**",
                 "httpMethod": "get", "algorithm": "TOKEN_BUCKET", "priority": 3,
                 "params": {"capacity": 7, "refill-per-sec": 0.5}}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.httpMethod").value("GET"))
                .andExpect(jsonPath("$.mode").value("STRICT"))
                .andExpect(jsonPath("$.failureMode").value("LOCAL_FALLBACK"))
                .andExpect(jsonPath("$.priority").value(3))
                // stored in canonical form whatever spelling was sent
                .andExpect(jsonPath("$.params.refill_per_sec").value(0.5))
                .andExpect(jsonPath("$.params.cost").value(1));

        assertThat(configVersion()).isEqualTo(2);
        assertThat(ruleCount()).isEqualTo(6);
    }

    @Test
    void allThreeAlgorithmsCanBeCreated() throws Exception {
        admin(post("/admin/rules").content("""
                {"name": "login", "limitBy": "IP", "endpointPattern": "/login", "algorithm": "SLIDING_WINDOW_LOG",
                 "params": {"limit": 5, "window_ms": 60000}}""")).andExpect(status().isCreated());
        admin(post("/admin/rules").content("""
                {"name": "render", "limitBy": "USER", "endpointPattern": "/render/**", "algorithm": "LEAKY_BUCKET_QUEUE",
                 "params": {"drain_per_sec": 10, "queue_capacity": 20}}""")).andExpect(status().isCreated());
        admin(post("/admin/rules").content("""
                {"name": "bulk", "limitBy": "USER", "endpointPattern": "/bulk/**", "algorithm": "TOKEN_BUCKET",
                 "mode": "LOCAL_SYNC", "params": {"capacity": 100, "refill_per_sec": 100}}""")).andExpect(status().isCreated());

        assertThat(configVersion()).isEqualTo(4);
    }

    @Test
    void invalidRulesAreRefusedWithAReasonAndNothingIsSaved() throws Exception {
        String bucket = "\"algorithm\": \"TOKEN_BUCKET\", \"params\": {\"capacity\": 5, \"refill_per_sec\": 1}";
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"TOKEN_BUCKET\", \"params\": {\"capcity\": 5, \"refill_per_sec\": 1}")), 400, "capcity");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"TOKEN_BUCKET\", \"params\": {\"capacity\": 0, \"refill_per_sec\": 1}")), 400, "capacity");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"TOKEN_BUCKET\", \"params\": {\"capacity\": \"5\", \"refill_per_sec\": 1}")), 400, "must be a number");
        assertRefused(post("/admin/rules").content(rule(bucket + ", \"prioritty\": 5")), 400, "Unknown field 'prioritty'");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"FIXED_WINDOW\", \"params\": {}")), 400, "Invalid value 'FIXED_WINDOW'");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"TOKEN_BUCKET\"")), 400, "'params' is required");
        assertRefused(post("/admin/rules").content(rule(bucket + ", \"matchTier\": \"gold\"")), 400, "unknown tier 'gold'");
        assertRefused(post("/admin/rules").content(rule(bucket + ", \"httpMethod\": \"FETCH\"")), 400, "HTTP method");
        assertRefused(post("/admin/rules").content(tokenBucket("r", "/api/**/x")), 400, "pattern");
        assertRefused(post("/admin/rules").content(tokenBucket("r", "api/x")), 400, "start with");
        assertRefused(post("/admin/rules").content("not json"), 400, "valid JSON");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"SLIDING_WINDOW_LOG\", \"mode\": \"LOCAL_SYNC\", \"params\": {\"limit\": 5, \"window_ms\": 1000}")), 400, "STRICT");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"LEAKY_BUCKET_QUEUE\", \"mode\": \"LOCAL_SYNC\", \"params\": {\"drain_per_sec\": 5, \"queue_capacity\": 5}")), 400, "not available");
        assertRefused(post("/admin/rules").content(rule("\"algorithm\": \"LEAKY_BUCKET_QUEUE\", \"params\": {\"drain_per_sec\": 1, \"queue_capacity\": 600}")), 400, "limit is 60 s");
        assertRefused(post("/admin/rules").content("""
                {"name": "r", "limitBy": "IP", "matchSubject": "2001:0DB8::1", "endpointPattern": "/x",
                 "algorithm": "TOKEN_BUCKET", "params": {"capacity": 1, "refill_per_sec": 1}}"""), 400, "canonical");
    }

    @Test
    void anInactiveRuleIsStillFullyValidated() throws Exception {
        assertRefused(post("/admin/rules").content(rule(
                "\"active\": false, \"algorithm\": \"TOKEN_BUCKET\", \"params\": {\"capacity\": -1, \"refill_per_sec\": 1}")),
                400, "capacity");
        assertRefused(post("/admin/rules").content("""
                {"name": "r", "limitBy": "USER", "endpointPattern": "/a/**/b", "active": false,
                 "algorithm": "TOKEN_BUCKET", "params": {"capacity": 1, "refill_per_sec": 1}}"""), 400, "pattern");
    }

    @Test
    void aSecondRuleForTheSameScopeIsAConflict() throws Exception {
        assertRefused(post("/admin/rules").content(tokenBucket("another", "/api/text/**")), 409, "already exists");
    }

    // ---- updating ----

    @Test
    void updatingReplacesTheRuleAndBumpsBothVersions() throws Exception {
        long id = idOf("free-images");

        admin(put("/admin/rules/" + id).content("""
                {"name": "free-images", "limitBy": "USER", "matchTier": "free", "endpointPattern": "/api/images/**",
                 "algorithm": "TOKEN_BUCKET", "params": {"capacity": 50, "refill_per_sec": 5}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.capacity").value(50))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(configVersion()).isEqualTo(2);
        assertThat(ruleCount()).isEqualTo(5);
    }

    @Test
    void anInvalidOrClashingUpdateLeavesTheOldRuleUntouched() throws Exception {
        long id = idOf("free-images");

        assertRefused(put("/admin/rules/" + id).content(rule("\"algorithm\": \"TOKEN_BUCKET\", \"params\": {\"capacity\": 0, \"refill_per_sec\": 1}")), 400, "capacity");
        // turning it into a second /api/text/** rule clashes with text-generation
        assertRefused(put("/admin/rules/" + id).content(tokenBucket("free-images", "/api/text/**")), 409, "already exists");
        assertRefused(put("/admin/rules/9999").content(tokenBucket("x", "/x")), 404, "9999");
    }

    // ---- deactivating ----

    @Test
    void deleteDeactivatesButKeepsTheRule() throws Exception {
        long id = idOf("free-images");

        admin(delete("/admin/rules/" + id)).andExpect(status().isNoContent());

        admin(get("/admin/rules/" + id)).andExpect(jsonPath("$.active").value(false));
        assertThat(ruleCount()).isEqualTo(5);
        assertThat(configVersion()).isEqualTo(2);

        admin(delete("/admin/rules/" + id)).andExpect(status().isNoContent()); // again: nothing changes
        assertThat(configVersion()).isEqualTo(2);
        admin(delete("/admin/rules/9999")).andExpect(status().isNotFound());
    }

    @Test
    void aDeactivatedScopeCanBeReusedAndThenTheOldRuleCannotComeBack() throws Exception {
        long old = idOf("text-generation");
        admin(delete("/admin/rules/" + old)).andExpect(status().isNoContent());
        admin(post("/admin/rules").content(tokenBucket("text-v2", "/api/text/**"))).andExpect(status().isCreated());

        // reactivating the old one would make two active rules for one scope
        assertRefused(put("/admin/rules/" + old).content(tokenBucket("text-generation", "/api/text/**")), 409, "already exists");
    }

    @Test
    void aStoredRuleThatIsInvalidIsReportedAndCanBeDeactivated() throws Exception {
        jdbc.sql("UPDATE ratelimit_rules SET params = '{\"capcity\": 1}'::jsonb WHERE name = 'pro-images'").update();
        long broken = idOf("pro-images");

        assertRefused(post("/admin/rules").content(tokenBucket("new", "/new/**")), 409, "pro-images");
        admin(get("/admin/rules/" + broken)).andExpect(status().isOk()); // still visible
        admin(delete("/admin/rules/" + broken)).andExpect(status().isNoContent());
        admin(post("/admin/rules").content(tokenBucket("new", "/new/**"))).andExpect(status().isCreated());
    }

    // ---- concurrency ----

    /** Runs the same number of admin requests at once and returns their status codes. */
    private List<Integer> concurrently(int count, java.util.function.IntFunction<String> bodyFor) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String content = bodyFor.apply(i);
                futures.add(executor.submit(() -> {
                    go.await();
                    return admin(post("/admin/rules").content(content)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        }
    }

    @Test
    void concurrentChangesEachGetTheirOwnVersion() throws Exception {
        List<Integer> statuses = concurrently(20, i -> tokenBucket("c" + i, "/concurrent/" + i));

        assertThat(statuses).containsOnly(201);
        assertThat(configVersion()).isEqualTo(21);
        assertThat(ruleCount()).isEqualTo(25);
    }

    @Test
    void concurrentCreationOfTheSameScopeLetsExactlyOneIn() throws Exception {
        List<Integer> statuses = concurrently(10, i -> tokenBucket("same" + i, "/same/**"));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
        assertThat(configVersion()).isEqualTo(2);
    }

    // ---- API keys ----

    @Test
    void anApiKeyIsShownOnceAndOnlyItsHashIsStored() throws Exception {
        ResultActions created = admin(post("/admin/api-keys").content("{\"userId\": \"alice\", \"tier\": \"pro\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"));
        String apiKey = body(created).get("apiKey").asText();
        String hash = body(created).get("keyHash").asText();

        assertThat(apiKey).startsWith("rlk_").hasSizeGreaterThan(40);
        assertThat(hash).isEqualTo(ApiKeyHash.of(apiKey)); // the same function the gateway looks keys up with
        assertThat(jdbc.sql("SELECT row_to_json(k)::text FROM api_keys k").query(String.class).single())
                .contains(hash).doesNotContain(apiKey);
        assertThat(admin(get("/admin/api-keys")).andReturn().getResponse().getContentAsString())
                .contains(hash).contains("alice").doesNotContain(apiKey);
        assertThat(configVersion()).isEqualTo(2);
    }

    @Test
    void everyKeyIsDifferent() throws Exception {
        String request = "{\"userId\": \"alice\", \"tier\": \"pro\"}";
        String first = body(admin(post("/admin/api-keys").content(request))).get("apiKey").asText();
        String second = body(admin(post("/admin/api-keys").content(request))).get("apiKey").asText();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aKeyCanBeRevokedOnce() throws Exception {
        String hash = body(admin(post("/admin/api-keys").content("{\"userId\": \"bob\", \"tier\": \"free\"}")))
                .get("keyHash").asText();

        admin(delete("/admin/api-keys/" + hash)).andExpect(status().isNoContent());
        admin(get("/admin/api-keys")).andExpect(jsonPath("$[0].active").value(false));
        assertThat(configVersion()).isEqualTo(3);

        admin(delete("/admin/api-keys/" + hash)).andExpect(status().isNoContent());
        assertThat(configVersion()).isEqualTo(3);
        admin(delete("/admin/api-keys/" + "0".repeat(64))).andExpect(status().isNotFound());
    }

    @Test
    void invalidKeyRequestsAreRefused() throws Exception {
        assertRefused(post("/admin/api-keys").content("{\"tier\": \"pro\"}"), 400, "userId");
        assertRefused(post("/admin/api-keys").content("{\"userId\": \"a\"}"), 400, "tier");
        assertRefused(post("/admin/api-keys").content("{\"userId\": \"a\", \"tier\": \"gold\"}"), 400, "unknown tier");
        assertRefused(post("/admin/api-keys").content("{\"userId\": \"a\", \"tier\": \"pro\", \"apiKey\": \"mine\"}"), 400, "apiKey");
        assertThat(jdbc.sql("SELECT count(*) FROM api_keys").query(Integer.class).single()).isZero();
    }
}
