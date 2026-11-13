package com.ratelimit.gateway.rules;

/**
 * Algorithm-specific parameters of a rule (the {@code params} JSON column). Only the token bucket
 * exists so far; the leaky bucket and sliding window variants are added with their algorithms.
 */
public sealed interface Params permits TokenBucketParams {
}
