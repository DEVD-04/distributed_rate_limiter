package com.ratelimit.gateway.rules;

import com.ratelimit.gateway.redis.RedisConnector;
import com.ratelimit.rules.snapshot.SnapshotKeys;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings published rules into the gateway. At startup, before any request is served, it uses the first
 * of these that gives usable rules:
 * <ol>
 *   <li>the snapshot published in Redis,
 *   <li>the copy this gateway saved last time,
 *   <li>{@code application.yml} and the built-in defaults.
 * </ol>
 * It never stops the gateway from starting. Without {@code gateway.redis.url}, rules come from
 * configuration only.
 */
public class RuleSnapshotLoader {

    /** What one attempt to take the rules from Redis led to. */
    public enum Refresh {
        APPLIED,
        UNCHANGED,
        /** Redis holds a snapshot this gateway refused; the rules in force are unchanged. */
        REJECTED,
        /** Redis is reachable but nothing has been published to it. */
        NOT_PUBLISHED,
        UNAVAILABLE
    }

    private static final Logger log = LoggerFactory.getLogger(RuleSnapshotLoader.class);

    /** Far longer than a limiter call's timeout: this is not on the request path and the value is larger. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final Optional<RedisConnector> redis;
    private final LiveRuleResolver resolver;
    private final RuleSnapshotFile file;

    public RuleSnapshotLoader(Optional<RedisConnector> redis, LiveRuleResolver resolver, RuleSnapshotFile file) {
        this.redis = redis;
        this.resolver = resolver;
        this.file = file;
    }

    public void loadAtStartup() {
        if (redis.isEmpty()) {
            log.info("Rules come from configuration (no gateway.redis.url set)");
            return;
        }
        Refresh fromRedis = refreshFromRedis();
        if (fromRedis == Refresh.APPLIED || fromRedis == Refresh.UNCHANGED) {
            return;
        }
        Optional<String> saved = file.read();
        if (saved.isPresent() && resolver.apply(saved.get(), LiveRuleResolver.Source.DISK) != LiveRuleResolver.Outcome.REJECTED) {
            log.warn("No usable rules in Redis ({}): using the copy saved by this gateway", fromRedis);
            return;
        }
        log.warn("No usable rules in Redis ({}) or on disk: using application.yml and the built-in defaults", fromRedis);
    }

    /** Takes the rules published in Redis, and saves a copy of any it puts into force. */
    public synchronized Refresh refreshFromRedis() {
        var connection = redis.flatMap(RedisConnector::ifConnected);
        if (connection.isEmpty()) {
            return Refresh.UNAVAILABLE;
        }
        String snapshot;
        try {
            snapshot = connection.get().async()
                    .hget(SnapshotKeys.KEY, SnapshotKeys.FIELD_SNAPSHOT)
                    .get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Refresh.UNAVAILABLE;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.debug("Cannot read the published rules: {}", e.toString());
            return Refresh.UNAVAILABLE;
        }
        if (snapshot == null) {
            return Refresh.NOT_PUBLISHED;
        }
        return switch (resolver.apply(snapshot, LiveRuleResolver.Source.REDIS)) {
            case APPLIED -> {
                file.save(snapshot);
                yield Refresh.APPLIED;
            }
            case UNCHANGED -> Refresh.UNCHANGED;
            case REJECTED -> Refresh.REJECTED;
        };
    }
}
