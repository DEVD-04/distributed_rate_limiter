package com.ratelimit.gateway.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the shared Redis connection, only when {@code gateway.redis.url} is set. */
@Configuration
@ConditionalOnExpression("!'${gateway.redis.url:}'.isBlank()")
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(RedisProperties properties) {
        RedisClient client = RedisClient.create(properties.url());
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build())
                .build());
        return client;
    }

    /** One connection is shared by all requests: Lettuce multiplexes commands over it safely. */
    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient client, RedisProperties properties) {
        StatefulRedisConnection<String, String> connection = client.connect();
        connection.setTimeout(properties.commandTimeout());
        return connection;
    }
}
