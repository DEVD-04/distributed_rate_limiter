package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisConfigTests {

    @Configuration
    @EnableConfigurationProperties(RedisProperties.class)
    static class Props {
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, RedisConfig.class);

    @Test
    void withoutAUrlThereIsNoRedisConnectionAtAll() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StatefulRedisConnection.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withAUrlAConnectionIsCreatedWithTheConfiguredTimeout() {
        runner.withPropertyValues("gateway.redis.url=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                "gateway.redis.command-timeout=40ms").run(ctx -> {
            StatefulRedisConnection<String, String> connection = ctx.getBean(StatefulRedisConnection.class);
            assertThat(connection.sync().ping()).isEqualTo("PONG");
            assertThat(connection.getTimeout().toMillis()).isEqualTo(40);
        });
    }

    @Test
    void theDefaultCommandTimeoutIsFifteenMilliseconds() {
        runner.run(ctx -> assertThat(ctx.getBean(RedisProperties.class).commandTimeout().toMillis()).isEqualTo(15));
    }

    @Test
    void redisCountsAsEnabledOnlyWithAUrl() {
        runner.run(ctx -> assertThat(ctx.getBean(RedisProperties.class).enabled()).isFalse());
        assertThat(new RedisProperties("redis://somewhere:6379", java.time.Duration.ofMillis(15), java.time.Duration.ofSeconds(1))
                .enabled()).isTrue();
    }
}
