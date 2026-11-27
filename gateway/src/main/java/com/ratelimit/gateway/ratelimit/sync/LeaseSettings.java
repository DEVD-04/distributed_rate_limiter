package com.ratelimit.gateway.ratelimit.sync;

import java.time.Duration;

/**
 * How a node borrows tokens.
 *
 * @param minBatch    the fewest tokens a call asks for
 * @param horizon     a batch is sized to the demand expected over this long. Borrowed tokens may be spent for
 *                    twice this long, and a "no tokens" reply is believed for at most this long
 * @param refillBelow when fewer than this fraction of the latest batch are left, more are fetched in the
 *                    background; 0 turns that off
 */
public record LeaseSettings(long minBatch, Duration horizon, double refillBelow) {

    public LeaseSettings {
        if (minBatch < 1) {
            throw new IllegalArgumentException("minimum batch must be at least 1, was " + minBatch);
        }
        if (horizon.toMillis() < 1 || horizon.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("lease horizon must be between 1 ms and 1 h, was " + horizon);
        }
        if (!(refillBelow >= 0 && refillBelow < 1)) {
            throw new IllegalArgumentException("refill-below must be at least 0 and below 1, was " + refillBelow);
        }
    }
}
