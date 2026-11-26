package com.ratelimit.policy.publish;

import com.ratelimit.policy.config.ConfigChangedEvent;
import com.ratelimit.rules.snapshot.ConfigSnapshot;
import com.ratelimit.rules.snapshot.SnapshotCodec;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the copy of the configuration in Redis equal to the database.
 *
 * <p>It does one thing, {@link #sync}, requested right after each change commits and run again every
 * few seconds. It compares by content, not only by version number, so it repairs whatever made the
 * copy wrong: a publish that failed, Redis restarting empty (it does not persist to disk), a damaged
 * or hand-edited value, or a database restored to an older version.
 *
 * <p><b>Publishing after a change happens on its own thread, never on the request's.</b> Just after a
 * commit the request still holds its database connection, and publishing needs another to read the
 * configuration. Done there, enough simultaneous changes would each hold one connection while waiting
 * for a second, exhausting the pool until they timed out. So the request only signals and returns;
 * signals arriving while a publish runs collapse into one more run. This also keeps a slow or
 * unreachable Redis out of the admin API's response times, and a failed publish never fails the change
 * that caused it.
 */
@Component
public class SnapshotPublisher {

    public enum Outcome {
        /** Publishing is turned off. */
        DISABLED,
        /** Redis already holds exactly this configuration. */
        UP_TO_DATE,
        PUBLISHED,
        /** A newer version was published while this one was being prepared. */
        SUPERSEDED,
        /** Redis or the database could not be used; it will be retried. */
        FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

    private final SnapshotReader reader;
    private final SnapshotStore store;
    private final AtomicBoolean failing = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();
    private final ExecutorService afterChange =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("snapshot-publisher").factory());

    public SnapshotPublisher(SnapshotReader reader, SnapshotStore store) {
        this.reader = reader;
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onConfigChanged(ConfigChangedEvent event) {
        requestSync();
    }

    /** Asks the publisher thread to {@link #sync}, unless a run that has not started yet is already due. */
    private void requestSync() {
        if (requested.compareAndSet(false, true)) {
            afterChange.execute(() -> {
                requested.set(false); // cleared before reading, so a change from now on asks for another run
                sync();
            });
        }
    }

    @PreDestroy
    void stop() {
        afterChange.shutdown(); // anything still unpublished is delivered by the first run after a restart
    }

    /** Runs at startup and then repeatedly. */
    @Scheduled(fixedDelayString = "${policy.publish.reconcile-interval:5s}")
    void reconcile() {
        sync();
    }

    public Outcome sync() {
        if (!store.enabled()) {
            return Outcome.DISABLED;
        }
        try {
            // Redis must be read before the database. Anything already in Redis was committed before the
            // database read below, so if Redis is still ahead, the database really has gone backwards.
            Optional<SnapshotStore.Stored> stored = store.read();
            ConfigSnapshot snapshot = reader.read();
            String text = SnapshotCodec.encode(snapshot);
            SnapshotCodec.decode(text); // never publish something a gateway would refuse to load

            if (stored.isPresent() && stored.get().version() == snapshot.version() && text.equals(stored.get().snapshot())) {
                recovered();
                return Outcome.UP_TO_DATE;
            }
            boolean databaseWentBack = stored.isPresent() && stored.get().version() > snapshot.version();
            if (databaseWentBack) {
                log.warn("Redis holds version {} but the database is at {} (reset or restored?): publishing the database's",
                        stored.get().version(), snapshot.version());
            }
            boolean written = store.write(snapshot.version(), text, databaseWentBack);
            recovered();
            if (!written) {
                return Outcome.SUPERSEDED;
            }
            log.info("Published configuration version {} ({} rules, {} API keys)",
                    snapshot.version(), snapshot.rules().size(), snapshot.apiKeys().size());
            return Outcome.PUBLISHED;
        } catch (RuntimeException e) {
            if (failing.compareAndSet(false, true)) { // once per outage, not every few seconds
                log.error("Cannot publish the configuration; gateways keep their current one. Retrying. Cause: {}", e.toString());
            }
            return Outcome.FAILED;
        }
    }

    /** The version gateways can currently load, if Redis can be asked. */
    public Optional<Long> publishedVersion() {
        if (!store.enabled()) {
            return Optional.empty();
        }
        try {
            return store.read().map(SnapshotStore.Stored::version);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void recovered() {
        if (failing.compareAndSet(true, false)) {
            log.info("Publishing works again");
        }
    }
}
