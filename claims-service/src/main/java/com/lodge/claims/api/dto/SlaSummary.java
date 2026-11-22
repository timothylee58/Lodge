package com.lodge.claims.api.dto;

/**
 * Open claims by SLA state. breached = the officer's (or the team's) own delays; chaseClaimant = claims past the
 * claimant's response target, which are NOT the officer's delay and are counted separately.
 */
public record SlaSummary(long breached, long dueSoon, long onTrack, long chaseClaimant) {}
