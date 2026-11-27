package com.ratelimit.gateway.redis;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.lettuce.core.RedisCommandInterruptedException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The circuit breaker in front of the Redis calls that limiting depends on: limit checks, token borrowing
 * and node heartbeats. One breaker serves them all, since they fail together.
 *
 * <p>While Redis answers, the breaker is <em>closed</em> and every call is made. Once enough of the latest
 * calls have failed it <em>opens</em>: calls fail at once, without waiting for a timeout and without adding
 * load to a struggling Redis. After a while it is <em>half open</em> and lets a few calls through; they
 * decide whether it closes or opens again. Nothing runs on a timer: the change to half open happens at the
 * first call after the wait. The heartbeat calls every second, so a gateway with no traffic recovers too.
 *
 * <p>Each node has its own breaker. It cannot tell a Redis outage from calls that were slow for its own
 * reasons (a burst, a JVM still warming up), and opens for either.
 */
public class RedisBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisBreaker.class);

    private final CircuitBreaker breaker; // null: no breaker, every call is made
    private final Duration openFor;

    public RedisBreaker(RedisBreakerProperties settings) {
        this.openFor = settings.openFor();
        this.breaker = CircuitBreaker.of("redis", CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.window())
                .minimumNumberOfCalls(settings.window()) // the library's default is 100
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openFor())
                .permittedNumberOfCallsInHalfOpenState(settings.probes())
                // the caller was told to stop waiting: says nothing about Redis
                .ignoreExceptions(RedisCommandInterruptedException.class)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> {
            var change = event.getStateTransition();
            // once per outage at the start and the end; the open / half-open rounds in between are detail
            if (change == CircuitBreaker.StateTransition.CLOSED_TO_OPEN) {
                log.warn("Redis circuit breaker opened: too many calls failed. Not calling Redis; trying again in {} ms",
                        settings.openFor().toMillis());
            } else if (event.getStateTransition().getToState() == CircuitBreaker.State.CLOSED) {
                log.info("Redis circuit breaker closed: calling Redis again");
            } else {
                log.debug("Redis circuit breaker: {}", change);
            }
        });
    }

    private RedisBreaker() {
        this.breaker = null;
        this.openFor = Duration.ZERO;
    }

    /** Makes every call: for tests of the things that use a breaker. */
    public static RedisBreaker disabled() {
        return new RedisBreaker();
    }

    /**
     * Makes the call, unless the breaker is open.
     *
     * @throws RedisSkippedException if the call was not made
     * @throws RuntimeException      whatever the call itself throws, which counts against Redis
     */
    public <T> T call(Supplier<T> redisCall) {
        if (breaker == null) {
            return redisCall.get();
        }
        try {
            return breaker.executeSupplier(redisCall);
        } catch (CallNotPermittedException e) {
            throw new RedisSkippedException();
        }
    }

    /**
     * What to tell a client that could not be served because of Redis to wait: the full open period while
     * the breaker is not closed (it does not say how much of it is left), otherwise a second, since a single
     * call failed and the next may well succeed.
     */
    public Duration retryAfter() {
        return isClosed() ? Duration.ofSeconds(1) : openFor;
    }

    /** Whether Redis is currently being called normally (the breaker is neither open nor half open). */
    public boolean isClosed() {
        return breaker == null || breaker.getState() == CircuitBreaker.State.CLOSED;
    }
}
