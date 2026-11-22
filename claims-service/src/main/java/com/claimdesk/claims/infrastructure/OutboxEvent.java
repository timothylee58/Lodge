package com.claimdesk.claims.infrastructure;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id private UUID id;
    private UUID aggregateId;
    private String eventType;
    private String topic;
    @JdbcTypeCode(SqlTypes.JSON) private String payload;
    private Instant createdAt;
    private Instant publishedAt;
    private int attempts;
    private String lastError;
    private Instant deadAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, UUID aggregateId, String eventType, String topic, String payload) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() { this.publishedAt = Instant.now(); }

    /** Counts a non-retriable failure; after maxAttempts the row is dead-lettered (skipped, kept for inspection). */
    public void recordFailure(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) this.deadAt = Instant.now();
    }

    public boolean isDead() { return deadAt != null; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getEventType() { return eventType; }
    public Instant getPublishedAt() { return publishedAt; }
}
