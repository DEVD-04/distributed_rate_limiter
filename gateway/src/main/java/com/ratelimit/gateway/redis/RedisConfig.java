package com.ratelimit.gateway.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sets up the Redis client and shared connection, only when {@code gateway.redis.url} is set. */
@Configuration
@ConditionalOnExpression("!'${gateway.redis.url:}'.isBlank()")
public class RedisConfig {

    /** How soon to try again after losing Redis, or after not reaching it at startup. */
    static final Duration RECONNECT_INTERVAL = Duration.ofSeconds(1);

    /**
     * The client library by default waits longer and longer between reconnection attempts, up to half a
     * minute, during which every limit would stay unenforced although Redis was back.
     */
    @Bean(destroyMethod = "shutdown")
    ClientResources redisClientResources() {
        return ClientResources.builder().reconnectDelay(Delay.constant(RECONNECT_INTERVAL)).build();
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(ClientResources resources, RedisProperties properties) {
        RedisURI uri = RedisURI.create(properties.url());
        // bounds the connection handshake, so a hung Redis cannot stall startup for the library's default minute
        uri.setTimeout(properties.connectTimeout().plusSeconds(1));
        RedisClient client = RedisClient.create(resources, uri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build())
                // While disconnected, fail at once. Queued commands would run after reconnecting and spend
                // tokens for requests that had already been let through.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        return client;
    }

    @Bean
    RedisBreaker redisBreaker(RedisBreakerProperties properties) {
        return new RedisBreaker(properties);
    }

    @Bean(destroyMethod = "close")
    RedisConnector redisConnector(RedisClient client, RedisProperties properties) {
        return new RedisConnector(client, properties.commandTimeout(), RECONNECT_INTERVAL);
    }
}
