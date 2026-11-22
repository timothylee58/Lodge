package com.lodge.claims.domain;

/**
 * A request that breaks a business rule (future incident date, amount above the coverage limit...) -> HTTP 400.
 * Deliberately its own type: a stray IllegalArgumentException from a library or a bug must surface as a 500,
 * not be reported to the client as their mistake (with an internal message attached).
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) { super(message); }
}
