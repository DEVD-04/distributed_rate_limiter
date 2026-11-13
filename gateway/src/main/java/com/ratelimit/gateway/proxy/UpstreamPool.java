package com.ratelimit.gateway.proxy;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The instances of one downstream service, served round-robin with passive health checking.
 *
 * <p>An instance that fails {@code failureThreshold} times in a row is ejected for {@code cooldown}.
 * After the cooldown it is eligible again, but its failure count is still at the threshold, so one more
 * failure ejects it immediately while one success clears it completely. If every instance is ejected
 * the one whose ejection ends soonest is used, so a fully failed service is probed rather than
 * short-circuited forever.
 *
 * <p>Time comes from a monotonic nanosecond clock, injectable for tests.
 */
public final class UpstreamPool {

    /** One backend instance and its health state. */
    public static final class Upstream {
        private final URI base;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile boolean ejected;
        private volatile long ejectedUntilNanos;

        private Upstream(URI base) {
            this.base = base;
        }

        public URI base() {
            return base;
        }

        private boolean isEjected(long nowNanos) {
            return ejected && nowNanos - ejectedUntilNanos < 0;
        }

        @Override
        public String toString() {
            return base.toString();
        }
    }

    private final String service;
    private final List<Upstream> instances;
    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoClock;
    private final AtomicInteger counter = new AtomicInteger();

    public UpstreamPool(String service, List<URI> bases, int failureThreshold, long cooldownNanos, LongSupplier nanoClock) {
        if (bases.isEmpty()) {
            throw new IllegalArgumentException("service " + service + " needs at least one instance");
        }
        this.service = service;
        this.instances = bases.stream().map(Upstream::new).toList();
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = cooldownNanos;
        this.nanoClock = nanoClock;
    }

    public String service() {
        return service;
    }

    public List<Upstream> instances() {
        return instances;
    }

    public int size() {
        return instances.size();
    }

    /**
     * Picks the next instance, round-robin, skipping ejected ones and those in {@code exclude}
     * (instances already tried for this request). If everything is excluded the exclusion is ignored.
     */
    public Upstream next(Set<Upstream> exclude) {
        long now = nanoClock.getAsLong();
        int n = instances.size();
        int start = Math.floorMod(counter.getAndIncrement(), n);
        Upstream soonestToRecover = null;
        for (int k = 0; k < n; k++) {
            Upstream candidate = instances.get((start + k) % n);
            if (exclude.contains(candidate)) {
                continue;
            }
            if (!candidate.isEjected(now)) {
                return candidate;
            }
            if (soonestToRecover == null || candidate.ejectedUntilNanos - soonestToRecover.ejectedUntilNanos < 0) {
                soonestToRecover = candidate;
            }
        }
        return soonestToRecover != null ? soonestToRecover : instances.get(start);
    }

    public void recordSuccess(Upstream upstream) {
        upstream.consecutiveFailures.set(0);
        upstream.ejected = false;
    }

    public void recordFailure(Upstream upstream) {
        int failures = upstream.consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            upstream.consecutiveFailures.set(failureThreshold); // stay at the threshold: no unbounded growth
            upstream.ejectedUntilNanos = nanoClock.getAsLong() + cooldownNanos;
            upstream.ejected = true;
        }
    }

    public boolean isEjected(Upstream upstream) {
        return upstream.isEjected(nanoClock.getAsLong());
    }
}
