package com.ratelimit.gateway.ratelimit.api;

/** Why a {@link Decision} came out the way it did. More reasons arrive with later phases. */
public enum Reason {
    ALLOWED,
    LIMIT_EXCEEDED,
    /** The leaky bucket queue was full (or the node had too many waiting requests), so the request was turned away. */
    QUEUE_FULL,
    /** The shared store could not be used and the rule fails open, so the request was let through unchecked. */
    LIMITER_UNAVAILABLE,
    /** The shared store could not be used and the rule fails closed, so the request was refused. */
    FAILED_CLOSED
}
