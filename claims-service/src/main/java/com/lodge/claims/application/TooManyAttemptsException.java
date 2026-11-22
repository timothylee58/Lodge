package com.lodge.claims.application;

/** Login throttled (per-IP rate limit or per-account lockout). Maps to 429 + Retry-After. */
public class TooManyAttemptsException extends RuntimeException {
    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super("Too many login attempts");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
