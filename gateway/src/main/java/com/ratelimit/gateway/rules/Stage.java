package com.ratelimit.gateway.rules;

/** The two points in the request pipeline where a rate limit is applied. */
public enum Stage {
    /** Before authentication: only the client IP is known. */
    PRE_AUTH,
    /** After authentication: user, API key and tier are known. */
    POST_AUTH
}
