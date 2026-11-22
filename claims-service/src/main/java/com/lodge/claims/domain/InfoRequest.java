package com.lodge.claims.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "info_request")
public class InfoRequest {
    @Id private UUID id;
    private UUID claimId;
    private String question;
    private String response;
    private UUID requestedBy;
    @Enumerated(EnumType.STRING) private InfoRequestStatus status;
    private Instant createdAt;
    private Instant answeredAt;

    protected InfoRequest() {}

    public InfoRequest(UUID claimId, String question, UUID requestedBy) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.question = question;
        this.requestedBy = requestedBy;
        this.status = InfoRequestStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public void answer(String response) {
        if (status != InfoRequestStatus.OPEN) throw new BusinessConflictException("Info request already answered");
        this.response = response;
        this.status = InfoRequestStatus.ANSWERED;
        this.answeredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public String getQuestion() { return question; }
    public String getResponse() { return response; }
    public InfoRequestStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAnsweredAt() { return answeredAt; }
}
