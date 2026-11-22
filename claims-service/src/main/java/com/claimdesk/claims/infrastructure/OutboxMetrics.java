package com.claimdesk.claims.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Alert signals (see docs/RUNBOOK.md for rules):
 *  claimdesk.outbox.dead                  > 0   -> an event could not be published and needs a human
 *  claimdesk.outbox.oldest.pending.seconds high -> publishing is stuck (broker down, publisher failing)
 */
@Component
public class OutboxMetrics {
    public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox, Clock clock) {
        Gauge.builder("claimdesk.outbox.dead", outbox, OutboxRepository::countByDeadAtIsNotNull)
                .description("Dead-lettered outbox rows awaiting operator action").register(registry);
        Gauge.builder("claimdesk.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNullAndDeadAtIsNull)
                .description("Outbox rows waiting to be published").register(registry);
        Gauge.builder("claimdesk.outbox.oldest.pending.seconds", outbox, o ->
                        o.findFirstByPublishedAtIsNullAndDeadAtIsNullOrderByCreatedAtAsc()
                                .map(e -> (double) Duration.between(e.getCreatedAt(), Instant.now(clock)).toSeconds()).orElse(0.0))
                .description("Age of the oldest unpublished outbox row").register(registry);
    }
}
