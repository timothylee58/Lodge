package com.lodge.claims.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "claim")
public class Claim {

    @Id private UUID id;
    @Column(nullable = false, unique = true) private String claimNumber;
    @Column(nullable = false) private UUID policyId;
    @Column(nullable = false) private UUID claimantId;
    private UUID assigneeId;

    @Enumerated(EnumType.STRING) private ClaimType type;
    @Enumerated(EnumType.STRING) private Market market;
    @Enumerated(EnumType.STRING) private ClaimStatus status;

    private LocalDate incidentDate;
    private String description;
    private BigDecimal reserveAmount = BigDecimal.ZERO;
    private BigDecimal settledAmount;
    private String currency;

    // Optimistic lock: concurrent "assign" by two officers -> second gets OptimisticLockException -> 409
    @Version private long version;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant statusChangedAt;

    protected Claim() {}

    public static Claim submit(String claimNumber, UUID policyId, UUID claimantId, ClaimType type,
                               Market market, LocalDate incidentDate, String description, String currency) {
        var c = new Claim();
        c.id = UUID.randomUUID();
        c.claimNumber = claimNumber;
        c.policyId = policyId;
        c.claimantId = claimantId;
        c.type = type;
        c.market = market;
        c.incidentDate = incidentDate;
        c.description = description;
        c.currency = currency;
        c.status = ClaimStatus.SUBMITTED;
        c.createdAt = c.updatedAt = c.statusChangedAt = Instant.now();
        return c;
    }

    public void assignTo(UUID officerId) {
        transitionTo(ClaimStatus.UNDER_REVIEW);
        this.assigneeId = officerId;
    }

    public void requestInfo()  { transitionTo(ClaimStatus.PENDING_INFO); }
    public void infoProvided() { transitionTo(ClaimStatus.UNDER_REVIEW); }

    public void approve(BigDecimal reserve) {
        requireNonNegative(reserve);
        transitionTo(ClaimStatus.APPROVED);
        this.reserveAmount = reserve;
    }

    public void reject() {
        transitionTo(ClaimStatus.REJECTED);
        this.reserveAmount = BigDecimal.ZERO;   // closed claims carry no exposure
    }

    public void settle(BigDecimal amount, BigDecimal coverageLimit) {
        requireNonNegative(amount);
        if (amount.compareTo(coverageLimit) > 0) {
            throw new InvalidRequestException("Settlement exceeds policy coverage limit");
        }
        transitionTo(ClaimStatus.SETTLED);
        this.settledAmount = amount;
        this.reserveAmount = BigDecimal.ZERO;
    }

    public void adjustReserve(BigDecimal reserve) {
        if (!status.isOpen()) throw new BusinessConflictException("Cannot change reserve on a closed claim");
        requireNonNegative(reserve);
        this.reserveAmount = reserve;
        this.updatedAt = Instant.now();
    }

    public boolean isAssignedTo(UUID officerId) { return officerId.equals(assigneeId); }

    private void transitionTo(ClaimStatus next) {
        if (!status.allowedNext().contains(next)) throw new InvalidTransitionException(status, next);
        this.status = next;
        this.updatedAt = this.statusChangedAt = Instant.now();
    }

    private static void requireNonNegative(BigDecimal v) {
        if (v == null || v.signum() < 0) throw new InvalidRequestException("Amount must be >= 0");
    }

    public UUID getId() { return id; }
    public String getClaimNumber() { return claimNumber; }
    public UUID getPolicyId() { return policyId; }
    public UUID getClaimantId() { return claimantId; }
    public UUID getAssigneeId() { return assigneeId; }
    public ClaimType getType() { return type; }
    public Market getMarket() { return market; }
    public ClaimStatus getStatus() { return status; }
    public BigDecimal getReserveAmount() { return reserveAmount; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public String getCurrency() { return currency; }
    public long getVersion() { return version; }
    public java.time.LocalDate getIncidentDate() { return incidentDate; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
}
