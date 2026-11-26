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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads rules from the database as {@code rules-core} objects, so every row passes the shared validation. */
@Repository
public class RuleRepository {

    private static final String SELECT_ACTIVE = """
            SELECT rule_id, name, limit_by, match_tier, match_subject, endpoint_pattern, http_method,
                   algorithm, mode, failure_mode, params::text AS params, priority, version
            FROM ratelimit_rules
            WHERE is_active
            ORDER BY rule_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RuleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @throws IllegalStateException if a stored rule is invalid, naming the rule */
    public List<Rule> findActive() {
        return jdbc.sql(SELECT_ACTIVE).query((row, n) -> toRule(row)).list();
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
                    ParamsFactory.parse(algorithm, paramsOf(row.getString("params"))),
                    row.getInt("priority"),
                    row.getLong("version"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stored rule " + id + " (" + name + ") is invalid: " + e.getMessage(), e);
        }
    }

    /** The {@code params} JSON object as the key/value form {@link ParamsFactory} understands. */
    private Map<String, String> paramsOf(String text) {
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("params is not valid JSON", e);
        }
        Map<String, String> params = new LinkedHashMap<>();
        node.properties().forEach(field -> {
            if (!field.getValue().isNumber()) {
                throw new IllegalArgumentException("parameter '" + field.getKey() + "' must be a number");
            }
            params.put(field.getKey(), field.getValue().asText());
        });
        return params;
    }
}
