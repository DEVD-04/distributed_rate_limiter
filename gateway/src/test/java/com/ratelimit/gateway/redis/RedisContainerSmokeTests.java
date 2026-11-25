package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves the build can start Redis in Docker and talk to it with Lettuce. */
@Testcontainers
class RedisContainerSmokeTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void lettuceCanTalkToRedisAndRunLua() {
        RedisClient client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            assertThat(connection.sync().ping()).isEqualTo("PONG");
            Long nowMs = connection.sync().eval(
                    "local t = redis.call('TIME'); return t[1] * 1000 + math.floor(t[2] / 1000)", ScriptOutputType.INTEGER);
            assertThat(nowMs).isGreaterThan(1_700_000_000_000L);
        } finally {
            client.shutdown();
        }
    }
}
