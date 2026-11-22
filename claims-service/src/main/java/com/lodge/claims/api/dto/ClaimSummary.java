package com.lodge.claims.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * For claimants: reserveAmount and SLA fields are null (internal figures), assigneeId is null and assigneeName is
 * the role label "A claims officer" - staff ids and names are internal.
 */
public record ClaimSummary(UUID id, String claimNumber, String status, String type, String market,
                           LocalDate incidentDate, String description, BigDecimal reserveAmount,
                           BigDecimal settledAmount, String currency, UUID assigneeId, String assigneeName,
                           Instant createdAt, Instant updatedAt, long version,
                           Instant statusChangedAt, String sla, Instant slaDueAt) {}
