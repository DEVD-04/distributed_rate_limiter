package com.ratelimit.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** How configuration properties become a rule set, including what happens when they are wrong. */
class RulesConfigTests {

    @Configuration
    @EnableConfigurationProperties(RulesProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, RulesConfig.class);

    private static final String[] VALID_RULE = {
            "gateway.ratelimit.rules[0].id=5",
            "gateway.ratelimit.rules[0].name=custom",
            "gateway.ratelimit.rules[0].limit-by=USER",
            "gateway.ratelimit.rules[0].endpoint-pattern=/custom/**",
            "gateway.ratelimit.rules[0].algorithm=TOKEN_BUCKET",
            "gateway.ratelimit.rules[0].params.capacity=7",
            "gateway.ratelimit.rules[0].params.refill-per-sec=3",
    };

    @Test
    void aRuleDefinedInConfigurationIsUsedWithItsDefaultsApplied() {
        runner.withPropertyValues(VALID_RULE).run(ctx -> {
            ResolvedRule r = ctx.getBean(RuleResolver.class)
                    .resolve(RuleContext.postAuth("free", "alice", null, "GET", "/custom/x"));
            assertThat(r.rule().name()).isEqualTo("custom");
            assertThat(r.rule().mode()).isEqualTo(Mode.STRICT);
            assertThat(r.rule().failureMode()).isEqualTo(FailureMode.LOCAL_FALLBACK);
            assertThat(r.rule().params()).isEqualTo(new TokenBucketParams(7, 3, 1));
        });
    }

    @Test
    void inactiveRulesAreIgnored() {
        runner.withPropertyValues(VALID_RULE).withPropertyValues("gateway.ratelimit.rules[0].active=false").run(ctx -> {
            ResolvedRule r = ctx.getBean(RuleResolver.class)
                    .resolve(RuleContext.postAuth("free", "alice", null, "GET", "/custom/x"));
            assertThat(r.rule().name()).isEqualTo("default-user");
        });
    }

    @Test
    void blankTierSubjectAndMethodMeanAny() {
        runner.withPropertyValues(VALID_RULE).withPropertyValues(
                "gateway.ratelimit.rules[0].match-tier=",
                "gateway.ratelimit.rules[0].match-subject=",
                "gateway.ratelimit.rules[0].http-method=").run(ctx -> {
            Rule rule = ((RuleSet) ctx.getBean(RuleResolver.class)).rules().stream()
                    .filter(r -> r.ruleId() == 5).findFirst().orElseThrow();
            assertThat(rule.matchTier()).isNull();
            assertThat(rule.matchSubject()).isNull();
            assertThat(rule.httpMethod()).isNull();
        });
    }

    @Test
    void aTypoInParamsFailsStartupNamingTheRule() {
        runner.withPropertyValues(VALID_RULE).withPropertyValues("gateway.ratelimit.rules[0].params.capcity=9").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("rules[0] (custom)");
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("capcity");
        });
    }

    @Test
    void twoRulesWithTheSameScopeFailStartup() {
        runner.withPropertyValues(VALID_RULE).withPropertyValues(
                "gateway.ratelimit.rules[1].id=6",
                "gateway.ratelimit.rules[1].name=clash",
                "gateway.ratelimit.rules[1].limit-by=USER",
                "gateway.ratelimit.rules[1].endpoint-pattern=/custom/**",
                "gateway.ratelimit.rules[1].algorithm=TOKEN_BUCKET",
                "gateway.ratelimit.rules[1].params.capacity=1",
                "gateway.ratelimit.rules[1].params.refill-per-sec=1").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("same scope");
        });
    }
}
