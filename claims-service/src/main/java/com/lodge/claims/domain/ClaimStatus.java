package com.lodge.claims.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ClaimStatus {
    SUBMITTED, UNDER_REVIEW, PENDING_INFO, APPROVED, SETTLED, REJECTED;

    // Single source of truth for allowed transitions; easy to unit-test and to explain.
    public Set<ClaimStatus> allowedNext() {
        return switch (this) {
            case SUBMITTED    -> EnumSet.of(UNDER_REVIEW);
            case UNDER_REVIEW -> EnumSet.of(PENDING_INFO, APPROVED, REJECTED);
            case PENDING_INFO -> EnumSet.of(UNDER_REVIEW);
            case APPROVED     -> EnumSet.of(SETTLED);
            case SETTLED, REJECTED -> EnumSet.noneOf(ClaimStatus.class);
        };
    }

    public boolean isOpen() {
        return this != SETTLED && this != REJECTED;
    }
}
