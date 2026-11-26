package com.ratelimit.rules;

import java.util.Locale;
import java.util.Objects;

/**
 * Everything a rule can be matched on for one request at one pipeline stage. A subject that is not
 * known at this stage (or for this kind of credential) is {@code null}, and rules that count by
 * it simply do not apply.
 *
 * @param stage    the pipeline stage being evaluated
 * @param tier     the caller's tier; {@code null} before authentication
 * @param method   upper-case HTTP method
 * @param path     request path
 * @param ip       canonical client IP
 * @param userId   authenticated user; {@code null} before authentication
 * @param apiKeyId identifier of the API key used; {@code null} if the caller used a JWT
 */
public record RuleContext(
        Stage stage, String tier, String method, String path, String ip, String userId, String apiKeyId) {

    public RuleContext {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        method = method.toUpperCase(Locale.ROOT);
        switch (stage) {
            case PRE_AUTH -> Objects.requireNonNull(ip, "ip is required before authentication");
            case POST_AUTH -> {
                Objects.requireNonNull(userId, "userId is required after authentication");
                Objects.requireNonNull(tier, "tier is required after authentication");
            }
        }
    }

    /** Context for the stage before authentication: only the client IP is known. */
    public static RuleContext preAuth(String ip, String method, String path) {
        return new RuleContext(Stage.PRE_AUTH, null, method, path, ip, null, null);
    }

    /** Context after authentication. {@code apiKeyId} is {@code null} for JWT callers. */
    public static RuleContext postAuth(String tier, String userId, String apiKeyId, String method, String path) {
        return new RuleContext(Stage.POST_AUTH, tier, method, path, null, userId, apiKeyId);
    }

    /** The value rules that count by {@code limitBy} are keyed on, or {@code null} if unknown. */
    public String subjectFor(LimitBy limitBy) {
        return switch (limitBy) {
            case IP -> ip;
            case USER -> userId;
            case API_KEY -> apiKeyId;
        };
    }

    /** Bit set of the {@link LimitBy} kinds this request can be counted by. */
    int availableSubjects() {
        int mask = 0;
        for (LimitBy by : LimitBy.values()) {
            if (subjectFor(by) != null) {
                mask |= 1 << by.ordinal();
            }
        }
        return mask;
    }
}
