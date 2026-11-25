package com.ratelimit.gateway.ratelimit.api;

/** Why a {@link Decision} came out the way it did. More reasons arrive with later phases. */
public enum Reason {
    ALLOWED,
    LIMIT_EXCEEDED,
    /** The shared store could not be reached, so the request was let through (until failure modes exist). */
    LIMITER_UNAVAILABLE
}
