package com.ratelimit.gateway.ratelimit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** A manually advanced monotonic clock for deterministic limiter tests. */
public final class FakeNanoClock implements LongSupplier {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long getAsLong() {
        return nanos.get();
    }

    public void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    public void advanceNanos(long delta) {
        nanos.addAndGet(delta);
    }

    public void set(long value) {
        nanos.set(value);
    }
}
