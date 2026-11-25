package com.ratelimit.gateway.rules;

/**
 * Algorithm-specific parameters of a rule (the {@code params} JSON column). The token bucket and the
 * sliding window log exist so far; the leaky bucket variant is added with its algorithm.
 */
public sealed interface Params permits TokenBucketParams, SlidingWindowParams {
}
