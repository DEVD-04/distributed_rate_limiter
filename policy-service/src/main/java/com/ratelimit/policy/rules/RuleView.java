package com.ratelimit.policy.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A stored rule as the admin API shows it. Read straight from the row without validating it, so a rule
 * that has somehow become invalid can still be seen (and then fixed or deactivated).
 */
public record RuleView(
        long ruleId,
        String name,
        String limitBy,
        String matchTier,
        String matchSubject,
        String endpointPattern,
        String httpMethod,
        String algorithm,
        String mode,
        String failureMode,
        JsonNode params,
        int priority,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
