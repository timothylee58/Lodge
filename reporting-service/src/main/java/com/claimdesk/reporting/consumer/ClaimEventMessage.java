package com.claimdesk.reporting.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot published by claims-service. Strings (not enums) for status/market/type so a new value added
 * upstream doesn't break this consumer. Unknown fields are ignored: producers may add fields freely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimEventMessage(UUID eventId, String eventType, Instant occurredAt, UUID claimId, String claimNumber,
                                Long version, String status, String market, String type, String currency,
                                BigDecimal reserveAmount, UUID assigneeId, String assigneeName) {

    public void requireValid() {
        if (eventId == null || claimId == null || version == null || occurredAt == null
                || isBlank(status) || isBlank(market) || isBlank(type) || isBlank(currency) || isBlank(claimNumber)) {
            throw new PoisonEventException("Event is missing required fields: " + this);
        }
    }

    public BigDecimal reserveOrZero() { return reserveAmount == null ? BigDecimal.ZERO : reserveAmount; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
