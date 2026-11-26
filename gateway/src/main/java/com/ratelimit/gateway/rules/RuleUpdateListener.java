package com.ratelimit.gateway.rules;

import com.ratelimit.rules.snapshot.SnapshotKeys;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies changes to the published rules while the gateway runs.
 *
 * <p>Two paths lead to the same reload:
 * <ul>
 *   <li><b>Announcements.</b> The policy service publishes a message after every publish, so a change
 *       normally applies almost at once.
 *   <li><b>Polling.</b> Pub/sub delivers nothing to a subscriber that is not connected at that moment, so
 *       the published rules are also re-read on a timer. The whole snapshot is compared, not only its
 *       version number: the policy service may repair a damaged copy without changing the version.
 * </ul>
 * Subscribing (again, after a lost connection) also triggers a reload, which catches whatever was
 * announced while this gateway could not hear it.
 *
 * <p>Every reload runs on this class's own single thread. Announcements arrive on the Redis client's
 * network thread, which must never block, and certainly not on a read from Redis. Announcements that
 * arrive while a reload is waiting to run are covered by it.
 */
public class RuleUpdateListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleUpdateListener.class);
    private static final Duration SUBSCRIBE_RETRY = Duration.ofSeconds(1);

    private final Optional<RedisClient> client;
    private final RuleSnapshotLoader loader;
    private final Duration pollInterval;
    private final boolean subscribe;
    private final AtomicBoolean reloadRequested = new AtomicBoolean();

    private ScheduledExecutorService worker;
    private StatefulRedisPubSubConnection<String, String> subscription; // used only on the worker, and in close()

    public RuleUpdateListener(Optional<RedisClient> client, RuleSnapshotLoader loader, Duration pollInterval,
            boolean subscribe) {
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("gateway.ratelimit.update-poll-interval must be positive");
        }
        this.client = client;
        this.loader = loader;
        this.pollInterval = pollInterval;
        this.subscribe = subscribe;
    }

    /** Does nothing without Redis: there are no published rules to follow. */
    public synchronized void start() {
        if (client.isEmpty()) {
            return;
        }
        worker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("rule-updates").factory());
        worker.scheduleWithFixedDelay(this::reload, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        if (subscribe) {
            // Retried until it first succeeds (Redis may be down at startup). After that the client library
            // resubscribes by itself whenever it reconnects.
            worker.scheduleWithFixedDelay(this::ensureSubscribed, 0, SUBSCRIBE_RETRY.toMillis(), TimeUnit.MILLISECONDS);
        }
        log.info("Watching for rule changes: {}, re-reading every {} ms",
                subscribe ? "listening for announcements" : "announcements off", pollInterval.toMillis());
    }

    private void ensureSubscribed() {
        if (subscription != null) {
            return;
        }
        StatefulRedisPubSubConnection<String, String> opened = null;
        try {
            opened = client.orElseThrow().connectPubSub();
            opened.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String version) {
                    requestReload();
                }

                @Override
                public void subscribed(String channel, long count) {
                    requestReload(); // first subscription, and every one after a reconnect
                }
            });
            opened.sync().subscribe(SnapshotKeys.CHANNEL);
            subscription = opened;
        } catch (RuntimeException e) {
            log.debug("Cannot subscribe to rule changes yet: {}", e.toString());
            if (opened != null) {
                opened.close();
            }
        }
    }

    /** Safe to call from any thread, including the Redis client's network thread. */
    private void requestReload() {
        if (reloadRequested.compareAndSet(false, true)) {
            try {
                worker.execute(() -> {
                    reloadRequested.set(false); // cleared before reading, so a later announcement asks again
                    reload();
                });
            } catch (RejectedExecutionException e) {
                log.debug("Ignoring a rule change announced during shutdown");
            }
        }
    }

    private void reload() {
        try {
            loader.refreshFromRedis();
        } catch (RuntimeException e) {
            // A periodic task that throws is never run again, so nothing may escape.
            log.error("Reloading the rules failed; will try again", e);
        }
    }

    @Override
    public synchronized void close() {
        if (worker == null) {
            return;
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (subscription != null) {
            subscription.close();
        }
    }
}
