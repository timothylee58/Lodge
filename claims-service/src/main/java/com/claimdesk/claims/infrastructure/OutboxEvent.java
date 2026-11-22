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
    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getEventType() { return eventType; }
    public Instant getPublishedAt() { return publishedAt; }
}
