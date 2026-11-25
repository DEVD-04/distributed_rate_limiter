package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisKeysTests {

    /** The hash tag Redis would use: text between the first open brace and the first close brace after it. */
    private static String hashTag(String key) {
        int open = key.indexOf('{');
        return key.substring(open + 1, key.indexOf('}', open));
    }

    @Test
    void keysCarryTheRuleAndSubjectInTheirHashTag() {
        String key = RedisKeys.of(7, "alice", "tb");

        assertThat(key).isEqualTo("rl:{7:alice}:tb");
        assertThat(hashTag(key)).isEqualTo("7:alice");
    }

    @Test
    void differentKindsOfOneSubjectShareAHashTag() {
        assertThat(hashTag(RedisKeys.of(7, "alice", "tb"))).isEqualTo(hashTag(RedisKeys.of(7, "alice", "log")));
    }

    @Test
    void bracesInASubjectCannotBreakTheHashTag() {
        String key = RedisKeys.of(7, "a}b{c", "tb");

        assertThat(hashTag(key)).isEqualTo("7:a%7Db%7Bc");
        assertThat(key).endsWith("}:tb");
    }

    @Test
    void escapingKeepsDistinctSubjectsDistinct() {
        assertThat(RedisKeys.of(1, "a%7Db", "tb")).isNotEqualTo(RedisKeys.of(1, "a}b", "tb"));
    }
}
