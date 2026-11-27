package com.ratelimit.gateway.ratelimit.sync;

import com.ratelimit.gateway.redis.RedisConnector;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What {@code LOCAL_SYNC} needs besides the shared bucket. Only with Redis: without it there are no other nodes. */
@Configuration
@ConditionalOnExpression("!'${gateway.redis.url:}'.isBlank()")
public class SyncConfig {

    /** The id is new at every start; an earlier run of this gateway drops out like any other node. */
    @Bean(initMethod = "start", destroyMethod = "close")
    NodeRegistry nodeRegistry(RedisConnector redis, ClusterProperties properties) {
        return new NodeRegistry(redis::connection, UUID.randomUUID().toString(),
                properties.heartbeatInterval(), properties.nodeTimeout());
    }
}
