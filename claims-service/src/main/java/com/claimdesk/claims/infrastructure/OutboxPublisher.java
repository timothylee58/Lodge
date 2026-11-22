package com.claimdesk.claims.infrastructure;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int maxAttempts;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                           @Value("${claimdesk.outbox.max-attempts:3}") int maxAttempts) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Deliberately NOT @Transactional around the send: no DB tx held open during network I/O.
     * At-least-once: a crash after send but before save re-sends -> consumers dedupe on eventId.
     *
     * Failure policy (the point of this method):
     *  - Retriable (broker down, timeout): stop this tick, retry next tick, count nothing. An outage is
     *    not the row's fault, so it must never dead-letter healthy rows.
     *  - Anything else (record too large, bad topic, serialisation...): stop this tick to preserve order,
     *    count the attempt; after maxAttempts dead-letter the row and carry on with the rest. Events are
     *    full claim snapshots carrying a version, so the next event for that claim repairs consumers.
     */
    @Scheduled(fixedDelayString = "${claimdesk.outbox.poll-ms}")
    public void publish() {
        for (var event : outbox.findTop100ByPublishedAtIsNullAndDeadAtIsNullOrderByCreatedAtAsc()) {
            try {
                var msg = MessageBuilder.withPayload(event.getPayload())
                        .setHeader(KafkaHeaders.TOPIC, event.getTopic())
                        .setHeader(KafkaHeaders.KEY, event.getAggregateId().toString()) // per-claim ordering
                        .setHeader("eventId", event.getId().toString())
                        .setHeader("eventType", event.getEventType())
                        .build();
                kafka.send(msg).get(10, TimeUnit.SECONDS);
                event.markPublished();
                outbox.save(event);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                var cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                if (isRetriable(cause)) {
                    log.warn("Outbox publish deferred for {} (retriable): {}", event.getId(), cause.toString());
                    return;
                }
                event.recordFailure(cause.toString(), maxAttempts);
                outbox.save(event);
                if (event.isDead()) {
                    log.error("Outbox event {} ({}) dead-lettered after {} attempts: {}",
                            event.getId(), event.getEventType(), event.getAttempts(), cause.toString());
                    continue; // unblock the rows behind it
                }
                log.warn("Outbox publish failed for {} (attempt {}/{}): {}",
                        event.getId(), event.getAttempts(), maxAttempts, cause.toString());
                return;
            }
        }
    }

    static boolean isRetriable(Throwable t) {
        if (t instanceof InterruptedException || t instanceof TimeoutException || t instanceof RetriableException) return true;
        // Producer-level problems that are not about this record (closed, buffer exhausted...).
        return t instanceof KafkaException && !(t instanceof ApiException) && !(t instanceof SerializationException);
    }
}
