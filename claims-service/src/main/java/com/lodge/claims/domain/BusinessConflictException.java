package com.lodge.claims.domain;

/** A valid request that conflicts with the current state of the data -> HTTP 409. */
public class BusinessConflictException extends RuntimeException {
    public BusinessConflictException(String message) { super(message); }
}
