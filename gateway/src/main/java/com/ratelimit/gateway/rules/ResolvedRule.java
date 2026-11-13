package com.ratelimit.gateway.rules;

/**
 * The rule chosen for a request, plus the limiter key to charge against it.
 *
 * @param rule the winning rule
 * @param key  the subject value for {@code rule.limitBy()} (the user id, API key id or IP)
 */
public record ResolvedRule(Rule rule, String key) {
}
