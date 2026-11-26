package com.ratelimit.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleSetTests {

    private static Rule rule(long id, LimitBy by, String tier, String subject, String pattern, String method, int priority) {
        return new Rule(id, "r" + id, by, tier, subject, pattern, method, Algorithm.TOKEN_BUCKET, Mode.STRICT,
                FailureMode.LOCAL_FALLBACK, new TokenBucketParams(10, 1), priority, 1);
    }

    private static Rule userRule(long id, String tier, String subject, String pattern) {
        return rule(id, LimitBy.USER, tier, subject, pattern, null, 0);
    }

    private static RuleSet set(Rule... rules) {
        return RuleSet.of(1, List.of(rules));
    }

    private static RuleContext post(String tier, String user, String apiKey, String method, String path) {
        return RuleContext.postAuth(tier, user, apiKey, method, path);
    }

    private static RuleContext post(String tier, String user, String path) {
        return post(tier, user, null, "GET", path);
    }

    private static long winner(RuleSet set, RuleContext ctx) {
        return set.resolve(ctx).rule().ruleId();
    }

    // ---- defaults ----

    @Test
    void anEmptyConfigurationStillLimitsEveryStage() {
        RuleSet set = set();

        ResolvedRule pre = set.resolve(RuleContext.preAuth("203.0.113.9", "GET", "/anything"));
        assertThat(pre.rule().name()).isEqualTo("default-ip");
        assertThat(pre.key()).isEqualTo("203.0.113.9");

        ResolvedRule post = set.resolve(post("free", "alice", "/anything"));
        assertThat(post.rule().name()).isEqualTo("default-user");
        assertThat(post.key()).isEqualTo("alice");
    }

    @Test
    void anOperatorDefinedCatchAllReplacesTheBuiltInDefault() {
        RuleSet set = set(userRule(100, null, null, "/**"));

        assertThat(winner(set, post("free", "alice", "/anything"))).isEqualTo(100);
        assertThat(set.rules()).extracting(Rule::ruleId).contains(100L, DefaultRules.IP_RULE_ID)
                .doesNotContain(DefaultRules.USER_RULE_ID);
    }

    @Test
    void aMoreSpecificRuleDoesNotRemoveTheBuiltInDefault() {
        RuleSet set = set(userRule(1, "free", null, "/**")); // tier-specific, so a different scope

        assertThat(winner(set, post("pro", "alice", "/x"))).isEqualTo(DefaultRules.USER_RULE_ID);
    }

    @Test
    void unmatchedPathsFallToTheDefault() {
        RuleSet set = set(userRule(1, null, null, "/api/images/**"));

        assertThat(winner(set, post("free", "alice", "/other"))).isEqualTo(DefaultRules.USER_RULE_ID);
    }

    // ---- individual precedence levels ----

    @Test
    void aTierRuleBeatsAGenericOneOnlyForThatTier() {
        RuleSet set = set(userRule(1, null, null, "/api/**"), userRule(2, "pro", null, "/api/**"));

        assertThat(winner(set, post("pro", "alice", "/api/x"))).isEqualTo(2);
        assertThat(winner(set, post("free", "alice", "/api/x"))).isEqualTo(1);
    }

    @Test
    void aSubjectRuleBeatsAGenericOneOnlyForThatSubject() {
        RuleSet set = set(userRule(1, null, null, "/api/**"), userRule(2, null, "alice", "/api/**"));

        assertThat(winner(set, post("free", "alice", "/api/x"))).isEqualTo(2);
        assertThat(winner(set, post("free", "bob", "/api/x"))).isEqualTo(1);
    }

    @Test
    void pathSpecificityOrdersExactThenSingleWildcardThenDeeperWildcardThenShorterThenCatchAll() {
        List<String> leastToMostSpecific = List.of("/**", "/api/**", "/api/images/**", "/api/images/*", "/api/images/x");

        for (int i = 0; i < leastToMostSpecific.size(); i++) {
            List<Rule> rules = new ArrayList<>();
            for (int j = 0; j <= i; j++) {
                rules.add(userRule(10 + j, null, null, leastToMostSpecific.get(j)));
            }
            // the catch-all configured as /** replaces the built-in, which is fine here
            assertThat(winner(RuleSet.of(1, rules), post("free", "alice", "/api/images/x")))
                    .as("with %s available", leastToMostSpecific.subList(0, i + 1))
                    .isEqualTo(10 + i);
        }
    }

    @Test
    void aMethodSpecificRuleBeatsAnyMethodAndIsIgnoredForOtherMethods() {
        RuleSet set = set(
                userRule(1, null, null, "/api/**"),
                rule(2, LimitBy.USER, null, null, "/api/**", "POST", 0));

        assertThat(winner(set, post("free", "alice", null, "POST", "/api/x"))).isEqualTo(2);
        assertThat(winner(set, post("free", "alice", null, "GET", "/api/x"))).isEqualTo(1);
    }

    @Test
    void methodMatchingIgnoresCase() {
        RuleSet set = set(rule(2, LimitBy.USER, null, null, "/api/**", "post", 0));

        assertThat(winner(set, post("free", "alice", null, "post", "/api/x"))).isEqualTo(2);
    }

    @Test
    void priorityBreaksTiesAndTheLowerIdBreaksEqualPriorities() {
        // Same tier/subject/path/method, so only priority can separate a USER rule from an API_KEY rule
        RuleSet userFirst = set(rule(1, LimitBy.USER, null, null, "/api/**", null, 9),
                rule(2, LimitBy.API_KEY, null, null, "/api/**", null, 1));
        RuleSet keyFirst = set(rule(1, LimitBy.USER, null, null, "/api/**", null, 1),
                rule(2, LimitBy.API_KEY, null, null, "/api/**", null, 9));
        RuleSet equal = set(rule(7, LimitBy.USER, null, null, "/api/**", null, 5),
                rule(3, LimitBy.API_KEY, null, null, "/api/**", null, 5));
        RuleContext viaKey = post("free", "alice", "key-1", "GET", "/api/x");

        assertThat(winner(userFirst, viaKey)).isEqualTo(1);
        assertThat(winner(keyFirst, viaKey)).isEqualTo(2);
        assertThat(winner(equal, viaKey)).isEqualTo(3);
    }

    // ---- precedence levels combined, in the documented order ----

    @Test
    void subjectOutranksTierOutranksPathOutranksMethodOutranksPriority() {
        Rule subjectOnly = userRule(1, null, "alice", "/**");
        Rule tierWithDeepPath = userRule(2, "free", null, "/api/images/x");
        Rule pathWithMethod = rule(3, LimitBy.USER, null, null, "/api/images/x", "GET", 0);
        Rule methodOnly = rule(4, LimitBy.USER, null, null, "/api/**", "GET", 100);
        Rule highPriorityGeneric = rule(5, LimitBy.USER, null, null, "/api/**", null, 1000);

        RuleContext ctx = post("free", "alice", "/api/images/x");

        assertThat(winner(set(subjectOnly, tierWithDeepPath, pathWithMethod, methodOnly, highPriorityGeneric), ctx)).isEqualTo(1);
        assertThat(winner(set(tierWithDeepPath, pathWithMethod, methodOnly, highPriorityGeneric), ctx)).isEqualTo(2);
        assertThat(winner(set(pathWithMethod, methodOnly, highPriorityGeneric), ctx)).isEqualTo(3);
        assertThat(winner(set(methodOnly, highPriorityGeneric), ctx)).isEqualTo(4);
        assertThat(winner(set(highPriorityGeneric), ctx)).isEqualTo(5);
    }

    @Test
    void aTierRuleOnABroadPathBeatsAGenericRuleOnANarrowPath() {
        RuleSet set = set(userRule(1, "free", null, "/api/**"), userRule(2, null, null, "/api/images/x"));

        assertThat(winner(set, post("free", "alice", "/api/images/x"))).isEqualTo(1);
    }

    @Test
    void aSubjectRuleOnABroadPathBeatsATierRuleOnANarrowPath() {
        RuleSet set = set(userRule(1, null, "alice", "/**"), userRule(2, "free", null, "/api/images/x"));

        assertThat(winner(set, post("free", "alice", "/api/images/x"))).isEqualTo(1);
    }

    @Test
    void aNonMatchingSubjectRuleFallsThroughToTheNextBest() {
        RuleSet set = set(userRule(1, null, "alice", "/api/**"), userRule(2, "free", null, "/api/**"));

        assertThat(winner(set, post("free", "bob", "/api/x"))).isEqualTo(2);
    }

    // ---- stages and subject kinds ----

    @Test
    void apiKeyRulesApplyOnlyToCallersUsingAnApiKey() {
        RuleSet set = set(rule(1, LimitBy.API_KEY, null, null, "/api/**", null, 0));

        assertThat(winner(set, post("free", "alice", null, "GET", "/api/x"))).isEqualTo(DefaultRules.USER_RULE_ID);
        ResolvedRule viaKey = set.resolve(post("free", "alice", "key-1", "GET", "/api/x"));
        assertThat(viaKey.rule().ruleId()).isEqualTo(1);
        assertThat(viaKey.key()).isEqualTo("key-1");
    }

    @Test
    void userRulesAreKeyedByUserEvenWhenTheCallerUsedAnApiKey() {
        RuleSet set = set(userRule(1, null, null, "/api/**"));

        ResolvedRule viaKey = set.resolve(post("free", "alice", "key-1", "GET", "/api/x"));
        assertThat(viaKey.rule().ruleId()).isEqualTo(1);
        assertThat(viaKey.key()).isEqualTo("alice");
    }

    @Test
    void ipRulesRunOnlyBeforeAuthAndIdentityRulesOnlyAfter() {
        RuleSet set = set(rule(1, LimitBy.IP, null, null, "/api/**", null, 0), userRule(2, null, null, "/api/**"));

        assertThat(winner(set, RuleContext.preAuth("203.0.113.9", "GET", "/api/x"))).isEqualTo(1);
        assertThat(winner(set, post("free", "alice", "/api/x"))).isEqualTo(2);
    }

    @Test
    void anIpSubjectRuleAppliesToThatAddressOnly() {
        RuleSet set = set(rule(1, LimitBy.IP, null, "203.0.113.9", "/**", null, 0));

        assertThat(winner(set, RuleContext.preAuth("203.0.113.9", "GET", "/x"))).isEqualTo(1);
        assertThat(winner(set, RuleContext.preAuth("203.0.113.10", "GET", "/x"))).isEqualTo(DefaultRules.IP_RULE_ID);
    }

    @Test
    void tierRulesNeverApplyBeforeAuthentication() {
        RuleSet set = set(rule(1, LimitBy.IP, "free", null, "/**", null, 0));

        assertThat(winner(set, RuleContext.preAuth("203.0.113.9", "GET", "/x"))).isEqualTo(DefaultRules.IP_RULE_ID);
    }

    // ---- memoization ----

    @Test
    void lookupsAreSharedAcrossSubjectsSoManyUsersCannotGrowTheCache() {
        RuleSet set = set(userRule(1, null, "alice", "/api/**"), userRule(2, null, null, "/api/**"));

        for (int i = 0; i < 1_000; i++) {
            set.resolve(post("free", "user-" + i, "/api/x"));
        }
        set.resolve(post("free", "alice", "/api/x"));

        assertThat(set.cachedCandidateLists()).isEqualTo(1);
        assertThat(winner(set, post("free", "alice", "/api/x"))).isEqualTo(1); // the subject rule still wins for alice
    }

    @Test
    void differentTiersMethodsAndPathsAreCachedSeparately() {
        RuleSet set = set();
        set.resolve(post("free", "alice", null, "GET", "/a"));
        set.resolve(post("pro", "alice", null, "GET", "/a"));
        set.resolve(post("free", "alice", null, "POST", "/a"));
        set.resolve(post("free", "alice", null, "GET", "/b"));
        set.resolve(post("free", "alice", "key", "GET", "/a")); // different available subjects

        assertThat(set.cachedCandidateLists()).isEqualTo(5);
    }

    @Test
    void theCacheIsBoundedAndResultsStayCorrectWhenItOverflows() {
        RuleSet set = RuleSet.of(1, List.of(userRule(1, null, null, "/api/**")), 50);

        for (int i = 0; i < 1_000; i++) {
            assertThat(winner(set, post("free", "alice", "/api/" + i))).isEqualTo(1);
        }
        assertThat(set.cachedCandidateLists()).isLessThanOrEqualTo(50);
    }

    // ---- validation ----

    @Test
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> set(userRule(1, null, null, "/a/**"), userRule(1, null, null, "/b/**")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate rule id 1");
    }

    @Test
    void twoRulesWithTheSameScopeAreRejected() {
        assertThatThrownBy(() -> set(userRule(1, "free", null, "/a/**"), userRule(2, "free", null, "/a/**")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same scope");
    }

    @Test
    void rulesDifferingOnlyInMethodOrLimitByAreDistinctScopes() {
        set(rule(1, LimitBy.USER, null, null, "/a/**", "GET", 0),
                rule(2, LimitBy.USER, null, null, "/a/**", "POST", 0),
                rule(3, LimitBy.API_KEY, null, null, "/a/**", "GET", 0));
    }

    @Test
    void nonPositiveIdsAreRejectedBecauseNegativeIdsAreReserved() {
        assertThatThrownBy(() -> set(userRule(0, null, null, "/a/**"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> set(userRule(-5, null, null, "/a/**"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unparseablePathPatternsAreRejected() {
        assertThatThrownBy(() -> set(userRule(1, null, null, "/api/**/x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid endpoint pattern");
    }
}
