package com.ratelimit.policy.rules;

import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.ParamsFactory;
import com.ratelimit.rules.Rule;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A whole rule as sent to create or replace one. Omitted optional fields take their defaults, on update
 * too: an update replaces the rule, it does not patch it. Blank {@code matchTier}, {@code matchSubject}
 * and {@code httpMethod} mean "any".
 */
public record RuleRequest(
        String name,
        LimitBy limitBy,
        String matchTier,
        String matchSubject,
        String endpointPattern,
        String httpMethod,
        Algorithm algorithm,
        Mode mode,
        FailureMode failureMode,
        Map<String, Object> params,
        Integer priority,
        Boolean active) {

    boolean activeOrDefault() {
        return active == null || active;
    }

    /**
     * Builds the rule with the same constructor and parameter parsing the gateway uses.
     *
     * @throws IllegalArgumentException if the request does not describe a valid rule
     */
    Rule toRule(long ruleId) {
        require(name, "name");
        require(limitBy, "limitBy");
        require(endpointPattern, "endpointPattern");
        require(algorithm, "algorithm");
        require(params, "params");

        Map<String, String> raw = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException("parameter '" + key + "' must be a number");
            }
            raw.put(key, value.toString());
        });
        return new Rule(ruleId, name.trim(), limitBy, blankToNull(matchTier), blankToNull(matchSubject),
                endpointPattern, blankToNull(httpMethod), algorithm,
                mode == null ? Mode.STRICT : mode,
                failureMode == null ? FailureMode.LOCAL_FALLBACK : failureMode,
                ParamsFactory.parse(algorithm, raw),
                priority == null ? 0 : priority,
                1);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("field '" + field + "' is required");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
