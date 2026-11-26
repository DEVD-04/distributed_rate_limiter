package com.ratelimit.policy.publish;

import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The published copy of the configuration in Redis.
 *
 * <p>The connection is opened on first use, not at startup, so the admin API works while Redis is down.
 * While disconnected, commands fail at once instead of queueing, and reconnection is retried every
 * second (the client library's default backs off to half a minute, which would leave gateways stale
 * long after Redis had returned).
 */
@Component
public class SnapshotStore {

    /** What Redis holds now; {@code version} is -1 if the stored version is unreadable. */
    public record Stored(long version, String snapshot) {
    }

    /**
     * Writes and announces a snapshot as one atomic step, refusing to replace a newer one (publishes can
     * overtake each other). KEYS[1] the hash; ARGV: version, snapshot, channel, force ('1' to overwrite
     * even a newer version, used when the database has been reset or restored).
     */
    private static final String WRITE = """
            local current = tonumber(redis.call('HGET', KEYS[1], 'version'))
            if ARGV[4] ~= '1' and current and current > tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], 'version', ARGV[1], 'snapshot', ARGV[2])
            redis.call('PUBLISH', ARGV[3], ARGV[1])
            return 1
            """;

    private final PolicyRedisProperties properties;
    private ClientResources resources;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    public SnapshotStore(PolicyRedisProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Optional<Stored> read() {
        List<KeyValue<String, String>> fields = connection().sync()
                .hmget(SnapshotKeys.KEY, SnapshotKeys.FIELD_VERSION, SnapshotKeys.FIELD_SNAPSHOT);
        String version = fields.get(0).getValueOrElse(null);
        String snapshot = fields.get(1).getValueOrElse(null);
        if (version == null && snapshot == null) {
            return Optional.empty();
        }
        return Optional.of(new Stored(parseVersion(version), snapshot));
    }

    /** @return false if Redis already holds a newer version and {@code force} was not set */
    public boolean write(long version, String snapshot, boolean force) {
        Long written = connection().sync().eval(WRITE, ScriptOutputType.INTEGER,
                new String[] {SnapshotKeys.KEY},
                Long.toString(version), snapshot, SnapshotKeys.CHANNEL, force ? "1" : "0");
        return written == 1;
    }

    private static long parseVersion(String version) {
        try {
            return version == null ? -1 : Long.parseLong(version);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection == null) {
            if (client == null) {
                resources = ClientResources.builder().reconnectDelay(Delay.constant(Duration.ofSeconds(1))).build();
                client = RedisClient.create(resources, properties.url());
                client.setOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build());
            }
            StatefulRedisConnection<String, String> opened = client.connect(); // throws if Redis is down: retried next time
            opened.setTimeout(properties.commandTimeout());
            connection = opened;
        }
        return connection;
    }

    @PreDestroy
    synchronized void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
            resources.shutdown();
        }
    }
}
