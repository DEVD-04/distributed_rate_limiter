package com.ratelimit.gateway.rules;

public enum Algorithm {
    TOKEN_BUCKET,
    LEAKY_BUCKET_QUEUE,
    SLIDING_WINDOW_LOG
}
