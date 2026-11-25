package com.ratelimit.gateway.redis;

/**
 * Redis key names. Every key of one (rule, subject) shares a hash tag, {@code {ruleId:subject}}, so a
 * future Redis Cluster would keep them in one slot. Redis takes the text between the first open brace
 * and the next close brace, so braces inside a subject (a user id is arbitrary text) are escaped to
 * keep the tag intact.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** State key for one rule and subject; {@code kind} names the algorithm's data (e.g. "tb"). */
    public static String of(long ruleId, String subject, String kind) {
        return "rl:{" + ruleId + ":" + escape(subject) + "}:" + kind;
    }

    private static String escape(String subject) {
        return subject.replace("%", "%25").replace("{", "%7B").replace("}", "%7D");
    }
}
