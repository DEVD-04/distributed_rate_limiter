package com.ratelimit.rules.snapshot;

/** Where the published configuration lives in Redis; shared by the publisher and the gateways. */
public final class SnapshotKeys {

    /**
     * A hash with the two fields below. One key rather than two, so a reader can never see a version that
     * does not belong to the snapshot next to it. It has no TTL, so {@code volatile-ttl} never evicts it.
     */
    public static final String KEY = "rl:rules";
    public static final String FIELD_VERSION = "version";
    public static final String FIELD_SNAPSHOT = "snapshot";

    /** Pub/sub channel; each message is the version just published. Messages can be lost. */
    public static final String CHANNEL = "rl:rules:updates";

    private SnapshotKeys() {
    }
}
