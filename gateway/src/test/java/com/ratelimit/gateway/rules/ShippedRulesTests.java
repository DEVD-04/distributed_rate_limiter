package com.ratelimit.gateway.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.rules.ResolvedRule;
import com.ratelimit.rules.RuleContext;
import com.ratelimit.rules.RuleResolver;
import com.ratelimit.rules.TokenBucketParams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The sample rules shipped in application.yml, loaded by the real application context. */
@SpringBootTest
class ShippedRulesTests {

    @Autowired
    RuleResolver resolver;

    @Test
    void freeUsersGetTheFreeImageRule() {
        ResolvedRule r = resolver.resolve(RuleContext.postAuth("free", "alice", null, "GET", "/api/images/cat.png"));

        assertThat(r.rule().name()).isEqualTo("free-images");
        assertThat(r.rule().params()).isEqualTo(new TokenBucketParams(10, 1));
        assertThat(r.key()).isEqualTo("alice");
    }

    @Test
    void proUsersGetTheProImageRule() {
        ResolvedRule r = resolver.resolve(RuleContext.postAuth("pro", "bob", null, "GET", "/api/images/cat.png"));

        assertThat(r.rule().name()).isEqualTo("pro-images");
        assertThat(r.rule().params()).isEqualTo(new TokenBucketParams(100, 20));
    }

    @Test
    void theTextRuleAppliesToEveryTier() {
        assertThat(resolver.resolve(RuleContext.postAuth("free", "alice", null, "POST", "/api/text/summarize")).rule().name())
                .isEqualTo("text-generation");
        assertThat(resolver.resolve(RuleContext.postAuth("pro", "bob", null, "POST", "/api/text/summarize")).rule().name())
                .isEqualTo("text-generation");
    }

    @Test
    void everythingElseGetsTheBuiltInDefaults() {
        assertThat(resolver.resolve(RuleContext.postAuth("free", "alice", null, "GET", "/api/other")).rule().name())
                .isEqualTo("default-user");
        assertThat(resolver.resolve(RuleContext.preAuth("203.0.113.9", "GET", "/api/other")).rule().name())
                .isEqualTo("default-ip");
    }
}
