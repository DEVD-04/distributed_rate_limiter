package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
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
    @EnableConfigurationProperties({RedisProperties.class, RedisBreakerProperties.class})
    static class Props {
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, RedisConfig.class);

    @Test
    void withoutAUrlThereIsNoRedisConnectionAtAll() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RedisConnector.class));
    }

    @Test
    void withAUrlTheConnectionIsReadyAtOnceWithTheConfiguredTimeout() {
        runner.withPropertyValues("gateway.redis.url=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                "gateway.redis.command-timeout=40ms").run(ctx -> {
            var connection = ctx.getBean(RedisConnector.class).connection();
            assertThat(connection.sync().ping()).isEqualTo("PONG");
            assertThat(connection.getTimeout().toMillis()).isEqualTo(40);
        });
    }

    @Test
    void startupSucceedsWithoutRedisAndTheConnectionAppearsWhenRedisDoes() {
        try (LateRedis redis = new LateRedis()) {
            runner.withPropertyValues("gateway.redis.url=" + redis.url()).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                RedisConnector connector = ctx.getBean(RedisConnector.class);
                assertThat(connector.ifConnected()).isEmpty();

                long start = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    assertThatThrownBy(connector::connection).isInstanceOf(RedisConnectionException.class);
                }
                // a request must never wait for a connection attempt
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));

                redis.start();

                await().atMost(Duration.ofSeconds(5)).until(() -> connector.ifConnected().isPresent());
                assertThat(connector.connection().sync().ping()).isEqualTo("PONG");
            });
        }
    }

    @Test
    void theDefaultCommandTimeoutIsFifteenMilliseconds() {
        runner.run(ctx -> assertThat(ctx.getBean(RedisProperties.class).commandTimeout().toMillis()).isEqualTo(15));
    }

    @Test
    void redisCountsAsEnabledOnlyWithAUrl() {
        runner.run(ctx -> assertThat(ctx.getBean(RedisProperties.class).enabled()).isFalse());
        assertThat(new RedisProperties("redis://somewhere:6379", Duration.ofMillis(15), Duration.ofSeconds(1)).enabled())
                .isTrue();
    }
}
