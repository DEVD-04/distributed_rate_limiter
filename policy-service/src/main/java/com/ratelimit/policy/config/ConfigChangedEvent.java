package com.ratelimit.policy.config;

/** A change saved rules or API keys. Raised inside the transaction; act on it only after commit. */
public record ConfigChangedEvent(long version) {
}
