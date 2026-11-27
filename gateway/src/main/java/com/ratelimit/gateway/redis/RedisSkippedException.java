package com.ratelimit.gateway.redis;

/**
 * A Redis call that was not made because the circuit breaker is open. Unlike any other failure, this one
 * is certain not to have reached Redis. It is thrown for every call during an outage, so it carries no
 * stack trace.
 */
public class RedisSkippedException extends RuntimeException {

    public RedisSkippedException() {
        super("Redis was not called: the circuit breaker is open", null, false, false);
    }
}
