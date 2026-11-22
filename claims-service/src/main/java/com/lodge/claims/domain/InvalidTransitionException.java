package com.lodge.claims.domain;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(ClaimStatus from, ClaimStatus to) {
        super("Cannot transition claim from %s to %s".formatted(from, to));
    }
}
