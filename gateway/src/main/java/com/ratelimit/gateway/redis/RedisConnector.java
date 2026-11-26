package com.ratelimit.gateway.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's shared Redis connection, which a gateway can start without.
 *
 * <p>It tries once while starting, so normally the connection is ready before the first request. If
 * Redis is not reachable, it keeps trying on a background thread. Until it succeeds,
 * {@link #connection()} fails <em>at once</em>: requests must never sit waiting for a connection
 * attempt. Once connected, the client library reconnects by itself after any later outage.
 *
 * <p>One connection serves all requests: Lettuce multiplexes commands over it safely.
 */
public class RedisConnector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisConnector.class);

    private final RedisClient client;
    private final Duration commandTimeout;
    private volatile StatefulRedisConnection<String, String> connection;
    private ScheduledExecutorService retries; // only exists if the first attempt failed
    private boolean closed;

    public RedisConnector(RedisClient client, Duration commandTimeout, Duration retryInterval) {
        this.client = client;
        this.commandTimeout = commandTimeout;
        if (!tryConnect()) {
            log.warn("Redis is not reachable: starting anyway and retrying every {} ms", retryInterval.toMillis());
            retries = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("redis-connect").factory());
            retries.scheduleWithFixedDelay(this::retry, retryInterval.toMillis(), retryInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** @throws RedisConnectionException immediately, if Redis has not been reached yet */
    public StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null) {
            throw new RedisConnectionException("Redis has not been reached yet");
        }
        return current;
    }

    /** The connection if Redis has ever been reached. It may still be temporarily disconnected. */
    public Optional<StatefulRedisConnection<String, String>> ifConnected() {
        return Optional.ofNullable(connection);
    }

    private void retry() {
        if (tryConnect()) {
            log.info("Connected to Redis");
            retries.shutdown();
        }
    }

    private synchronized boolean tryConnect() {
        if (closed || connection != null) {
            return connection != null;
        }
        try {
            StatefulRedisConnection<String, String> opened = client.connect();
            opened.setTimeout(commandTimeout);
            connection = opened;
            return true;
        } catch (RuntimeException e) {
            log.debug("Redis connection attempt failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (retries != null) {
            retries.shutdownNow();
        }
        if (connection != null) {
            connection.close();
        }
    }
}
