package com.ratelimit.mock;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** Decides how long a request should be delayed and whether it should fail. */
@Component
public class FaultInjector {

    private final MockProperties properties;

    public FaultInjector(MockProperties properties) {
        this.properties = properties;
    }

    /** Delay in milliseconds: the fixed latency plus a random share of the jitter.  It skips the random call when jitter is 0. */
    public long delayMillis() {
        long jitter = properties.latencyJitterMs() == 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.latencyJitterMs() + 1);
        return properties.latencyMs() + jitter;
    }

    public boolean shouldFail() {
        double rate = properties.errorRate();
        if (rate <= 0.0) {
            return false;
        }
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
    }
}
