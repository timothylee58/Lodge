package com.lodge.claims.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Audit trail + claimant timeline. Append-only. */
@Entity
@Table(name = "claim_event")
public class ClaimEvent {
    @Id private UUID id;
    private UUID claimId;
    private String type;
    private UUID actorId;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, Object> payload;
    private Instant createdAt;

    protected ClaimEvent() {}

    public ClaimEvent(UUID claimId, String type, UUID actorId, Map<String, Object> payload) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.type = type;
        this.actorId = actorId;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public String getType() { return type; }
    public UUID getActorId() { return actorId; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
