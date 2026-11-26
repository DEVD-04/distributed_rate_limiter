package com.ratelimit.rules;

/**
 * Algorithm-specific parameters of a rule (the {@code params} JSON column). All three algorithms exist:
 * token bucket, sliding window log and leaky bucket (queue variant).
 */
public sealed interface Params permits TokenBucketParams, SlidingWindowParams, LeakyBucketParams {
}
