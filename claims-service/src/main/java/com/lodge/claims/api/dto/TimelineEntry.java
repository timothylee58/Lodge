package com.lodge.claims.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * actor: staff see the person's name; claimants see a role label ("You", "Claims officer", "Lodge").
 * actorId: staff only, null for claimants (staff ids are internal).
 */
public record TimelineEntry(String type, Instant at, UUID actorId, String actor, Map<String, Object> detail) {}
