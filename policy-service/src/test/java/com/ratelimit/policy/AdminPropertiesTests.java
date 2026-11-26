package com.ratelimit.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.policy.admin.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The service must not start with the admin API unprotected or weakly protected. */
class AdminPropertiesTests {

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class);

    @Test
    void startupFailsWithoutAToken() {
        runner.run(ctx -> assertThat(ctx.getStartupFailure()).hasStackTraceContaining("POLICY_ADMIN_TOKEN"));
        runner.withPropertyValues("policy.admin-token=").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("policy.admin-token=                    ").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startupFailsWithAShortToken() {
        runner.withPropertyValues("policy.admin-token=too-short").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void aLongEnoughTokenIsAcceptedAndNeverPrinted() {
        runner.withPropertyValues("policy.admin-token=0123456789abcdef").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AdminProperties.class).toString()).doesNotContain("0123456789abcdef");
        });
    }
}
