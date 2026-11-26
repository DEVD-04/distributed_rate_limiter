package com.ratelimit.rules;

/** What to do when the shared store (Redis) is unavailable. Applied from the resilience phase on. */
public enum FailureMode {
    FAIL_OPEN,
    FAIL_CLOSED,
    LOCAL_FALLBACK
}
