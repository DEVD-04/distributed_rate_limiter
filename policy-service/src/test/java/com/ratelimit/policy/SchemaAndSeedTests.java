package com.ratelimit.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ratelimit.policy.rules.RuleRepository;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.RuleSet;
import com.ratelimit.rules.TokenBucketParams;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The migrations against a real PostgreSQL: what they create, what they seed, and what the database
 * itself refuses. Tests that write successfully are transactional, so they roll back.
 */
@SpringBootTest
@Import(PostgresTestSupport.class)
class SchemaAndSeedTests {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RuleRepository rules;

    private static final String HASH = "a".repeat(64);

    /** Inserts a rule that is valid except for whatever the arguments change. */
    private int insertRule(String limitBy, String tier, String pattern, String method, String algorithm, String mode,
            String params, boolean active) {
        return jdbc.sql("""
                INSERT INTO ratelimit_rules
                    (name, limit_by, match_tier, endpoint_pattern, http_method, algorithm, mode, params, is_active)
                VALUES ('extra', :limitBy, :tier, :pattern, :method, :algorithm, :mode, :params::jsonb, :active)
                """)
                .param("limitBy", limitBy).param("tier", tier).param("pattern", pattern).param("method", method)
                .param("algorithm", algorithm).param("mode", mode).param("params", params).param("active", active)
                .update();
    }

    private int insertTokenBucket(String limitBy, String tier, String pattern, boolean active) {
        return insertRule(limitBy, tier, pattern, null, "TOKEN_BUCKET", "STRICT",
                "{\"capacity\": 5, \"refill_per_sec\": 1}", active);
    }

    // ---- migrations and seed ----

    @Test
    void bothMigrationsRanSuccessfully() {
        List<String> applied = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();

        assertThat(applied).containsExactly("1", "2");
    }

    @Test
    void theThreeTiersAreSeeded() {
        assertThat(jdbc.sql("SELECT name FROM tiers").query(String.class).list())
                .containsExactlyInAnyOrder("free", "pro", "enterprise");
    }

    @Test
    void theConfigurationStartsAtVersionOneAndCanOnlyHaveOneRow() {
        assertThat(jdbc.sql("SELECT version FROM config_version").query(Long.class).list()).containsExactly(1L);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO config_version (version) VALUES (2)").update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theSeededRulesPassTheSharedValidationAndFormAWorkingRuleSet() {
        List<Rule> seeded = rules.findActive();

        assertThat(seeded).extracting(Rule::name)
                .containsExactly("default-ip", "default-user", "free-images", "pro-images", "text-generation");

        RuleSet set = RuleSet.of(1, seeded); // exactly what a gateway would build
        assertThat(set.rules()).allSatisfy(r -> assertThat(r.ruleId()).isPositive()); // the seeded defaults replace the built-ins

        assertThat(winner(set, RuleContext.postAuth("free", "u", null, "GET", "/api/images/a"))).isEqualTo("free-images");
        assertThat(winner(set, RuleContext.postAuth("pro", "u", null, "GET", "/api/images/a"))).isEqualTo("pro-images");
        assertThat(winner(set, RuleContext.postAuth("enterprise", "u", null, "GET", "/api/images/a"))).isEqualTo("default-user");
        assertThat(winner(set, RuleContext.postAuth("free", "u", null, "POST", "/api/text/a"))).isEqualTo("text-generation");
        assertThat(winner(set, RuleContext.preAuth("203.0.113.9", "GET", "/anything"))).isEqualTo("default-ip");
    }

    @Test
    void seededParamsMatchTheSampleRulesTheGatewayShipsWith() {
        assertThat(rules.findActive()).filteredOn(r -> r.name().equals("free-images")).singleElement()
                .satisfies(r -> assertThat(r.params()).isEqualTo(new TokenBucketParams(10, 1)));
        assertThat(rules.findActive()).filteredOn(r -> r.name().equals("pro-images")).singleElement()
                .satisfies(r -> assertThat(r.params()).isEqualTo(new TokenBucketParams(100, 20)));
        assertThat(rules.findActive()).filteredOn(r -> r.name().equals("text-generation")).singleElement()
                .satisfies(r -> assertThat(r.params()).isEqualTo(new TokenBucketParams(30, 5)));
    }

    private static String winner(RuleSet set, RuleContext context) {
        return set.resolve(context).rule().name();
    }

    // ---- the repository ----

    @Test
    @Transactional
    void inactiveRulesAreNotReturned() {
        jdbc.sql("UPDATE ratelimit_rules SET is_active = false WHERE name = 'free-images'").update();

        assertThat(rules.findActive()).extracting(Rule::name).doesNotContain("free-images").hasSize(4);
    }

    @Test
    @Transactional
    void allThreeAlgorithmsCanBeStoredAndRead() {
        insertRule("USER", null, "/sw/**", null, "SLIDING_WINDOW_LOG", "STRICT", "{\"limit\": 5, \"window_ms\": 60000}", true);
        insertRule("USER", null, "/lb/**", null, "LEAKY_BUCKET_QUEUE", "STRICT", "{\"drain_per_sec\": 2.5, \"queue_capacity\": 10}", true);

        assertThat(rules.findActive()).hasSize(7);
    }

    @Test
    @Transactional
    void aStoredRuleWithBadParamsIsReportedByItsIdAndName() {
        insertRule("USER", null, "/bad/**", null, "TOKEN_BUCKET", "STRICT", "{\"capcity\": 5, \"refill_per_sec\": 1}", true);

        assertThatThrownBy(rules::findActive).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("(extra)").hasMessageContaining("capcity");
    }

    @Test
    @Transactional
    void aStoredNonNumericParamIsRejected() {
        insertRule("USER", null, "/bad/**", null, "TOKEN_BUCKET", "STRICT", "{\"capacity\": \"5\", \"refill_per_sec\": 1}", true);

        assertThatThrownBy(rules::findActive).hasMessageContaining("'capacity' must be a number");
    }

    // ---- what the database refuses on its own ----

    @Test
    void aSecondActiveRuleForTheSameScopeIsRefused() {
        assertThatThrownBy(() -> insertTokenBucket("USER", "free", "/api/images/**", true))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_rule_scope");
    }

    @Test
    void aSecondRuleWithNoTierSubjectOrMethodIsAlsoRefused() {
        // NULLs are folded to '' by the index; without that, SQL would treat each NULL as distinct.
        assertThatThrownBy(() -> insertTokenBucket("USER", null, "/api/text/**", true))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @Transactional
    void aDeactivatedRuleMayRepeatAScope() {
        assertThat(insertTokenBucket("USER", "free", "/api/images/**", false)).isEqualTo(1);
    }

    @Test
    void slidingWindowWithLocalSyncIsRefused() {
        assertThatThrownBy(() -> insertRule("USER", null, "/a/**", null, "SLIDING_WINDOW_LOG", "LOCAL_SYNC",
                "{\"limit\": 1, \"window_ms\": 1}", true))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("sliding_window_is_strict");
    }

    @Test
    void anUnknownTierIsRefused() {
        assertThatThrownBy(() -> insertTokenBucket("USER", "gold", "/a/**", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownEnumValuesAndBadShapesAreRefused() {
        // one statement per assertion: outside a transaction, a refused insert leaves nothing behind
        assertThatThrownBy(() -> insertTokenBucket("DEVICE", null, "/a/**", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTokenBucket("USER", null, "no-leading-slash", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRule("USER", null, "/a/**", "FETCH", "TOKEN_BUCKET", "STRICT", "{}", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRule("USER", null, "/a/**", null, "FIXED_WINDOW", "STRICT", "{}", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRule("USER", null, "/a/**", null, "TOKEN_BUCKET", "STRICT", "[1, 2]", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anApiKeyMustBeStoredAsASha256Hash() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO api_keys (key_hash, user_id, tier) VALUES ('my-raw-key', 'u', 'free')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void aHashedApiKeyForAKnownTierIsAccepted() {
        assertThat(jdbc.sql("INSERT INTO api_keys (key_hash, user_id, tier) VALUES (:hash, 'u', 'pro')")
                .param("hash", HASH).update()).isEqualTo(1);
    }
}
