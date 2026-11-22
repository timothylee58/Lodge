package com.lodge.claims.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Alert signals (see docs/RUNBOOK.md for rules):
 *  lodge.outbox.dead                  > 0   -> an event could not be published and needs a human
 *  lodge.outbox.oldest.pending.seconds high -> publishing is stuck (broker down, publisher failing)
 */
@Component
public class OutboxMetrics {
    public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox, Clock clock) {
        Gauge.builder("lodge.outbox.dead", outbox, OutboxRepository::countByDeadAtIsNotNull)
                .description("Dead-lettered outbox rows awaiting operator action").register(registry);
        Gauge.builder("lodge.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNullAndDeadAtIsNull)
                .description("Outbox rows waiting to be published").register(registry);
        Gauge.builder("lodge.outbox.oldest.pending.seconds", outbox, o ->
                        o.findFirstByPublishedAtIsNullAndDeadAtIsNullOrderByCreatedAtAsc()
                                .map(e -> (double) Duration.between(e.getCreatedAt(), Instant.now(clock)).toSeconds()).orElse(0.0))
                .description("Age of the oldest unpublished outbox row").register(registry);
    }
}
