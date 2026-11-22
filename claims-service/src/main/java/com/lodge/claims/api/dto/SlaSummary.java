package com.lodge.claims.api.dto;

/** Open claims by SLA state, for one slice of the queue. */
public record SlaSummary(long breached, long dueSoon, long onTrack) {}
