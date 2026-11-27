package com.ratelimit.gateway.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimit.gateway.ratelimit.RateLimiterRouter;
import com.ratelimit.gateway.ratelimit.api.RateLimiter;
import com.ratelimit.gateway.ratelimit.sync.ClusterProperties;
import com.ratelimit.gateway.ratelimit.sync.LeaseProperties;
import com.ratelimit.gateway.ratelimit.sync.SyncConfig;
import com.ratelimit.gateway.redis.RedisConfig;
import com.ratelimit.gateway.redis.RedisProperties;
import com.ratelimit.gateway.rules.RulesConfig;
import com.ratelimit.gateway.rules.RulesProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Which limiters the gateway builds from configuration, and what it refuses to start with. */
@Testcontainers
class RateLimitConfigTests {

    @Configuration
    @EnableConfigurationProperties(
            {RulesProperties.class, RedisProperties.class, ClusterProperties.class, LeaseProperties.class})
    static class Props {
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    Props.class, RedisConfig.class, RulesConfig.class, SyncConfig.class, RateLimitConfig.class)
            .withPropertyValues("gateway.ratelimit.snapshot-file="); // never pick up a saved copy from this machine

    private static String redisUrl() {
        return "gateway.redis.url=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static String[] rule(int i, String name, String algorithm, String mode, String... params) {
        String prefix = "gateway.ratelimit.rules[" + i + "].";
        List<String> p = new ArrayList<>(List.of(
                prefix + "id=" + (i + 1),
                prefix + "name=" + name,
                prefix + "limit-by=USER",
                prefix + "endpoint-pattern=/r" + i + "/**",
                prefix + "algorithm=" + algorithm,
                prefix + "mode=" + mode));
        for (String keyValue : params) {
            p.add(prefix + "params." + keyValue);
        }
        return p.toArray(String[]::new);
    }

    private static String[] concat(String[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray(String[]::new);
    }

    @Test
    void withNothingConfiguredTheGatewayLimitsInMemory() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RateLimiter.class)).isInstanceOf(RateLimiterRouter.class);
        });
    }

    @Test
    void tokenBucketRulesInAnyModeStartWithoutRedis() {
        runner.withPropertyValues(concat(
                rule(0, "a", "TOKEN_BUCKET", "STRICT", "capacity=5", "refill-per-sec=1"),
                rule(1, "b", "TOKEN_BUCKET", "LOCAL_SYNC", "capacity=5", "refill-per-sec=1")))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aRuleThatNeedsRedisStopsStartupWhenThereIsNone() {
        runner.withPropertyValues(rule(0, "login-window", "SLIDING_WINDOW_LOG", "STRICT", "limit=5", "window-ms=1000"))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("'login-window'")
                            .hasStackTraceContaining("gateway.redis.url");
                });
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        runner.withPropertyValues(concat(
                rule(0, "first", "SLIDING_WINDOW_LOG", "STRICT", "limit=5", "window-ms=1000"),
                rule(1, "second", "LEAKY_BUCKET_QUEUE", "STRICT", "drain-per-sec=10", "queue-capacity=5")))
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("'first'").hasStackTraceContaining("'second'"));
    }

    @Test
    void withRedisAllAlgorithmsStart() {
        runner.withPropertyValues(concat(new String[] {redisUrl()},
                rule(0, "tb", "TOKEN_BUCKET", "STRICT", "capacity=5", "refill-per-sec=1"),
                rule(1, "sw", "SLIDING_WINDOW_LOG", "STRICT", "limit=5", "window-ms=1000"),
                rule(2, "lb", "LEAKY_BUCKET_QUEUE", "STRICT", "drain-per-sec=10", "queue-capacity=5")))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void impossibleBorrowingSettingsStopStartup() {
        runner.withPropertyValues(redisUrl(), "gateway.lease.refill-below=1.5")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasStackTraceContaining("refill-below"));
    }

    @Test
    void localSyncLeakyBucketIsRefusedEvenWithRedis() {
        runner.withPropertyValues(concat(new String[] {redisUrl()},
                rule(0, "lb-local", "LEAKY_BUCKET_QUEUE", "LOCAL_SYNC", "drain-per-sec=10", "queue-capacity=5")))
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .hasStackTraceContaining("LOCAL_SYNC").hasStackTraceContaining("'lb-local'"));
    }
}
