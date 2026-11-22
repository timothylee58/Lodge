package com.claimdesk.claims.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    // Deliberately NOT @Transactional around the send: don't hold a DB tx open during network I/O.
    // At-least-once: a crash after send but before save re-sends -> consumers dedupe on event id.
    @Scheduled(fixedDelayString = "${claimdesk.outbox.poll-ms}")
    public void publish() {
        for (var event : outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                var msg = MessageBuilder.withPayload(event.getPayload())
                        .setHeader(KafkaHeaders.TOPIC, event.getTopic())
                        .setHeader(KafkaHeaders.KEY, event.getAggregateId().toString()) // per-claim ordering
                        .setHeader("eventId", event.getId().toString())
                        .setHeader("eventType", event.getEventType())
                        .build();
                kafka.send(msg).get(10, TimeUnit.SECONDS); // bounded wait (scaffold had unbounded .get())
                event.markPublished();
                outbox.save(event);
            } catch (Exception e) {
                log.warn("Outbox publish failed for {}, will retry", event.getId(), e);
                return; // stop to preserve ordering; retry next tick
            }
        }
    }
}
