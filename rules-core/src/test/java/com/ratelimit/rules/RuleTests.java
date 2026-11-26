package com.ratelimit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuleTests {

    private static Rule rule(String name, LimitBy by, String tier, String subject, String pattern, String method, Mode mode) {
        return new Rule(1, name, by, tier, subject, pattern, method, Algorithm.TOKEN_BUCKET, mode,
                FailureMode.LOCAL_FALLBACK, new TokenBucketParams(10, 1), 0, 1);
    }

    private static Rule valid() {
        return rule("ok", LimitBy.USER, null, null, "/api/**", null, Mode.STRICT);
    }

    @Test
    void aValidRuleIsAccepted() {
        assertThat(valid().name()).isEqualTo("ok");
    }

    @Test
    void httpMethodsAreNormalizedToUpperCase() {
        assertThat(rule("r", LimitBy.USER, null, null, "/a", "post", Mode.STRICT).httpMethod()).isEqualTo("POST");
    }

    @Test
    void rejectsInvalidFields() {
        assertThatThrownBy(() -> rule(" ", LimitBy.USER, null, null, "/a", null, Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> rule("r", LimitBy.USER, null, null, "api/a", null, Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("start with '/'");
        assertThatThrownBy(() -> rule("r", LimitBy.USER, null, null, "/a", "FETCH", Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP method");
        assertThatThrownBy(() -> rule("r", LimitBy.USER, " ", null, "/a", null, Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tier");
        assertThatThrownBy(() -> rule("r", LimitBy.USER, null, "", "/a", null, Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject");
    }

    @Test
    void ipSubjectsMustBeCanonicalSoTheyCanBeComparedAsText() {
        rule("r", LimitBy.IP, null, "203.0.113.9", "/a", null, Mode.STRICT);
        rule("r", LimitBy.IP, null, "2001:db8::1", "/a", null, Mode.STRICT);
        assertThatThrownBy(() -> rule("r", LimitBy.IP, null, "2001:0DB8:0:0:0:0:0:1", "/a", null, Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");
        assertThatThrownBy(() -> rule("r", LimitBy.IP, null, "not-an-ip", "/a", null, Mode.STRICT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonIpSubjectsAreFreeText() {
        rule("r", LimitBy.USER, null, "Alice_42", "/a", null, Mode.STRICT);
    }

    @Test
    void slidingWindowLogCannotBeLocalSync() {
        assertThatThrownBy(() -> Rule.validateCombination(Algorithm.SLIDING_WINDOW_LOG, Mode.LOCAL_SYNC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSlidingWindowRuleIsAcceptedOnlyInStrictMode() {
        Rule strict = new Rule(1, "sw", LimitBy.USER, null, null, "/a", null, Algorithm.SLIDING_WINDOW_LOG, Mode.STRICT,
                FailureMode.LOCAL_FALLBACK, new SlidingWindowParams(5, 1000), 0, 1);
        assertThat(strict.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_LOG);
        assertThatThrownBy(() -> new Rule(1, "sw", LimitBy.USER, null, null, "/a", null, Algorithm.SLIDING_WINDOW_LOG,
                Mode.LOCAL_SYNC, FailureMode.LOCAL_FALLBACK, new SlidingWindowParams(5, 1000), 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STRICT");
    }

    @Test
    void paramsMustMatchTheAlgorithm() {
        assertThatThrownBy(() -> new Rule(1, "x", LimitBy.USER, null, null, "/a", null, Algorithm.SLIDING_WINDOW_LOG,
                Mode.STRICT, FailureMode.LOCAL_FALLBACK, new TokenBucketParams(5, 1), 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("do not fit");
        assertThatThrownBy(() -> new Rule(1, "x", LimitBy.USER, null, null, "/a", null, Algorithm.TOKEN_BUCKET,
                Mode.STRICT, FailureMode.LOCAL_FALLBACK, new SlidingWindowParams(5, 1000), 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("do not fit");
    }

    @Test
    void allOtherCombinationsAreValid() {
        Rule.validateCombination(Algorithm.SLIDING_WINDOW_LOG, Mode.STRICT);
        Rule.validateCombination(Algorithm.TOKEN_BUCKET, Mode.STRICT);
        Rule.validateCombination(Algorithm.TOKEN_BUCKET, Mode.LOCAL_SYNC);
        Rule.validateCombination(Algorithm.LEAKY_BUCKET_QUEUE, Mode.STRICT);
        Rule.validateCombination(Algorithm.LEAKY_BUCKET_QUEUE, Mode.LOCAL_SYNC);
    }

    @Test
    void limitByDeterminesTheStage() {
        assertThat(LimitBy.IP.stage()).isEqualTo(Stage.PRE_AUTH);
        assertThat(LimitBy.USER.stage()).isEqualTo(Stage.POST_AUTH);
        assertThat(LimitBy.API_KEY.stage()).isEqualTo(Stage.POST_AUTH);
    }

    @Test
    void ruleContextRequiresTheSubjectsOfItsStage() {
        assertThatThrownBy(() -> RuleContext.preAuth(null, "GET", "/")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RuleContext.postAuth("free", null, null, "GET", "/")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RuleContext.postAuth(null, "alice", null, "GET", "/")).isInstanceOf(NullPointerException.class);
    }
}
