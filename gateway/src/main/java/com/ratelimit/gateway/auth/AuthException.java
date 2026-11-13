package com.ratelimit.gateway.auth;

/**
 * Authentication failed. The {@link Reason} is for logs and metrics only; clients are always
 * told the same generic message so the response does not help someone probing for valid keys.
 */
public class AuthException extends Exception {

    public enum Reason {
        MISSING_CREDENTIALS,
        MALFORMED,
        UNSUPPORTED_ALGORITHM,
        UNKNOWN_KID,
        BAD_SIGNATURE,
        EXPIRED,
        NOT_YET_VALID,
        INVALID_CLAIMS,
        UNKNOWN_API_KEY
    }

    private final Reason reason;

    public AuthException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
