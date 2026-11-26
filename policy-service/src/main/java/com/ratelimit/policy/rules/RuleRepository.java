package com.ratelimit.policy.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.ParamsFactory;
import com.ratelimit.rules.Rule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The {@code ratelimit_rules} table. {@link #findActive()} returns {@code rules-core} objects, so every
 * row a gateway would receive passes the shared validation.
 */
@Repository
public class RuleRepository {

    private static final String SELECT = """
            SELECT rule_id, name, limit_by, match_tier, match_subject, endpoint_pattern, http_method,
                   algorithm, mode, failure_mode, params::text AS params, priority, is_active, version,
                   created_at, updated_at
            FROM ratelimit_rules
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RuleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @throws IllegalStateException if a stored rule is invalid, naming the rule */
    public List<Rule> findActive() {
        return jdbc.sql(SELECT + " WHERE is_active ORDER BY rule_id").query((row, n) -> toRule(row)).list();
    }

    /** Every rule, including deactivated ones. */
    public List<RuleView> findAll() {
        return jdbc.sql(SELECT + " ORDER BY rule_id").query((row, n) -> toView(row)).list();
    }

    public Optional<RuleView> findById(long ruleId) {
        return jdbc.sql(SELECT + " WHERE rule_id = :id").param("id", ruleId).query((row, n) -> toView(row)).optional();
    }

    /** @return the new rule's id (assigned by the database; the id in {@code rule} is ignored) */
    public long insert(Rule rule, boolean active) {
        KeyHolder keys = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO ratelimit_rules (name, limit_by, match_tier, match_subject, endpoint_pattern, http_method,
                    algorithm, mode, failure_mode, params, priority, is_active)
                VALUES (:name, :limitBy, :matchTier, :matchSubject, :pattern, :method,
                    :algorithm, :mode, :failureMode, :params::jsonb, :priority, :active)
                """), rule, active)
                .update(keys, "rule_id");
        return keys.getKey().longValue();
    }

    public void update(long ruleId, Rule rule, boolean active) {
        bind(jdbc.sql("""
                UPDATE ratelimit_rules SET name = :name, limit_by = :limitBy, match_tier = :matchTier,
                    match_subject = :matchSubject, endpoint_pattern = :pattern, http_method = :method,
                    algorithm = :algorithm, mode = :mode, failure_mode = :failureMode, params = :params::jsonb,
                    priority = :priority, is_active = :active, version = version + 1, updated_at = now()
                WHERE rule_id = :id
                """), rule, active)
                .param("id", ruleId)
                .update();
    }

    public void deactivate(long ruleId) {
        jdbc.sql("UPDATE ratelimit_rules SET is_active = false, version = version + 1, updated_at = now() WHERE rule_id = :id")
                .param("id", ruleId)
                .update();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Rule rule, boolean active) {
        return statement
                .param("name", rule.name())
                .param("limitBy", rule.limitBy().name())
                .param("matchTier", rule.matchTier())
                .param("matchSubject", rule.matchSubject())
                .param("pattern", rule.endpointPattern())
                .param("method", rule.httpMethod())
                .param("algorithm", rule.algorithm().name())
                .param("mode", rule.mode().name())
                .param("failureMode", rule.failureMode().name())
                .param("params", write(ParamsFactory.toMap(rule.params())))
                .param("priority", rule.priority())
                .param("active", active);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot write params", e);
        }
    }

    private Rule toRule(ResultSet row) throws SQLException {
        long id = row.getLong("rule_id");
        String name = row.getString("name");
        try {
            Algorithm algorithm = Algorithm.valueOf(row.getString("algorithm"));
            return new Rule(id, name,
                    LimitBy.valueOf(row.getString("limit_by")),
                    row.getString("match_tier"),
                    row.getString("match_subject"),
                    row.getString("endpoint_pattern"),
                    row.getString("http_method"),
                    algorithm,
                    Mode.valueOf(row.getString("mode")),
                    FailureMode.valueOf(row.getString("failure_mode")),
                    ParamsFactory.parse(algorithm, numericParams(row.getString("params"))),
                    row.getInt("priority"),
                    row.getLong("version"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stored rule " + id + " (" + name + ") is invalid: " + e.getMessage(), e);
        }
    }

    private RuleView toView(ResultSet row) throws SQLException {
        return new RuleView(
                row.getLong("rule_id"),
                row.getString("name"),
                row.getString("limit_by"),
                row.getString("match_tier"),
                row.getString("match_subject"),
                row.getString("endpoint_pattern"),
                row.getString("http_method"),
                row.getString("algorithm"),
                row.getString("mode"),
                row.getString("failure_mode"),
                readTree(row.getString("params")),
                row.getInt("priority"),
                row.getBoolean("is_active"),
                row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("params is not valid JSON", e);
        }
    }

    /** The {@code params} JSON object as the key/value form {@link ParamsFactory} understands. */
    private Map<String, String> numericParams(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        readTree(text).properties().forEach(field -> {
            if (!field.getValue().isNumber()) {
                throw new IllegalArgumentException("parameter '" + field.getKey() + "' must be a number");
            }
            params.put(field.getKey(), field.getValue().asText());
        });
        return params;
    }
}
