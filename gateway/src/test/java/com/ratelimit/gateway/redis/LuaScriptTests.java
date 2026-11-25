package com.ratelimit.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LuaScriptTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    private static final LuaScript ADD = new LuaScript("add", "return {tonumber(ARGV[1]) + tonumber(ARGV[2]), #KEYS}");

    @Test
    void runsAScriptWithKeysAndArguments() {
        assertThat(ADD.run(connection, List.of("k1", "k2"), "2", "3")).containsExactly(5L, 2L);
    }

    @Test
    void recoversWhenRedisForgetsTheScript() {
        ADD.run(connection, List.of(), "1", "1");
        connection.sync().scriptFlush();

        assertThat(ADD.run(connection, List.of(), "4", "5")).containsExactly(9L, 0L);
    }

    @Test
    void scriptErrorsPropagateInsteadOfBeingRetried() {
        LuaScript broken = new LuaScript("broken", "return redis.call('NOSUCHCOMMAND')");
        assertThatThrownBy(() -> broken.run(connection, List.of()))
                .isInstanceOf(RedisCommandExecutionException.class);
    }
}
