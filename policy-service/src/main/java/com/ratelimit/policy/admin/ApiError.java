package com.ratelimit.policy.admin;

/** The body of every error the admin API returns. */
public record ApiError(String error, String message) {
}
