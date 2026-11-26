package com.ratelimit.gateway.ratelimit.local;

import static com.ratelimit.gateway.ratelimit.local.Fixed.NANOS_PER_SECOND;
import static com.ratelimit.gateway.ratelimit.local.Fixed.ceilMulSubDiv;
import static com.ratelimit.gateway.ratelimit.local.Fixed.mulAddDivMod;

import com.ratelimit.rules.TokenBucketParams;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Token bucket state for one (rule, key). Tokens are counted in micro-tokens (millionths) and
 * refilled lazily on each call, so there are no timers.
 *
 * <p>Refill is exact integer arithmetic: {@code elapsed_ns * rate_micro_per_s} is a whole number of
 * billionths of a micro-token, and whatever does not yet add up to a full micro-token is carried in
 * {@code remainder}. Nothing is rounded away, so a bucket polled a million times a second refills at
 * exactly the same speed as one polled once a minute.
 *
 * <p>Balance, timestamp and remainder change together, so each bucket has its own lock. The
 * critical section is a few arithmetic operations with no I/O, so contention is limited to callers
 * of the same key. A {@link ReentrantLock} is used rather than {@code synchronized} so virtual
 * threads are never pinned.
 *
 * <p><b>The clock is read while holding the lock.</b> If a thread read the time first and queued for
 * the lock afterwards, a later reading could be applied before an earlier one, and the bucket would
 * see time run backwards. A bucket also never moves its timestamp backwards, so even a misbehaving
 * clock can only make it stall, never credit the same time twice (which would over-admit).
 *
 * <p>The refill parameters are supplied on every call rather than stored, so a rule edit takes
 * effect immediately while the bucket keeps its balance (clamped if the capacity shrank).
 *
 * <p>All times come from one monotonic {@code nanoTime}-style clock; wall-clock time is never mixed in.
 */
final class LocalTokenBucket {

    /** Result of one {@link #tryAcquire} call. */
    record Outcome(boolean allowed, long remainingMicro, long retryAfterNanos, long resetNanos) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile long idleTtlNanos;
    private volatile TokenBucketParams seenParams;

    private boolean primed;
    private long micro;      // current balance in micro-tokens
    private long remainder;  // carried refill, in billionths of a micro-token; always < 1_000_000_000
    private long lastNanos;  // latest time accounted for; never decreases

    /**
     * @param params       the rule parameters in force when the bucket is created
     * @param idleTtlNanos how long the bucket may sit unused before it is safe to drop
     */
    LocalTokenBucket(TokenBucketParams params, long idleTtlNanos) {
        this.seenParams = params;
        this.idleTtlNanos = idleTtlNanos;
    }

    long idleTtlNanos() {
        return idleTtlNanos;
    }

    void setIdleTtlNanos(long ttlNanos) {
        this.idleTtlNanos = ttlNanos;
    }

    /** Notes the params of the current call; true if they differ from the previous call's. */
    boolean observe(TokenBucketParams params) {
        TokenBucketParams previous = seenParams;
        if (previous == params) {
            return false;
        }
        seenParams = params;
        return !params.equals(previous);
    }

    Outcome tryAcquire(TokenBucketParams params, long costMicro, LongSupplier nanoClock) {
        long capacityMicro = params.capacityMicro();
        long rate = params.refillMicroPerSecond();
        lock.lock();
        try {
            long nowNanos = nanoClock.getAsLong();
            if (!primed) {
                primed = true;
                micro = capacityMicro; // a new bucket starts full
                remainder = 0;
                lastNanos = nowNanos;
            } else {
                refill(capacityMicro, rate, nowNanos);
            }

            boolean allowed = micro >= costMicro;
            long retryNanos = 0;
            if (allowed) {
                micro -= costMicro;
            } else {
                retryNanos = ceilMulSubDiv(costMicro - micro, NANOS_PER_SECOND, remainder, rate);
            }
            long resetNanos = micro >= capacityMicro
                    ? 0
                    : ceilMulSubDiv(capacityMicro - micro, NANOS_PER_SECOND, remainder, rate);
            return new Outcome(allowed, micro, retryNanos, resetNanos);
        } finally {
            lock.unlock();
        }
    }

    private void refill(long capacityMicro, long ratePerSec, long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed > 0) {
            lastNanos = nowNanos;
        }

        if (micro >= capacityMicro) {
            micro = capacityMicro; // also clamps after the capacity was lowered
            remainder = 0;         // a full bucket accrues nothing
            return;
        }
        if (elapsed <= 0) {
            return; // no time has passed (or the clock went backwards): nothing to credit
        }
        long[] qr = mulAddDivMod(elapsed, ratePerSec, remainder, NANOS_PER_SECOND);
        long added = qr[0];
        if (added >= capacityMicro - micro) {
            micro = capacityMicro;
            remainder = 0;
        } else {
            micro += added;
            remainder = qr[1];
        }
    }
}
