package com.claimdesk.claims.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TimelineEntry(String type, Instant at, UUID actorId, Map<String, Object> detail) {}
