package com.lodge.claims.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "policy")
public class Policy {
    @Id private UUID id;
    private String policyNumber;
    private UUID holderId;
    @Enumerated(EnumType.STRING) private ClaimType type;
    @Enumerated(EnumType.STRING) private Market market;
    private BigDecimal coverageLimit;
    private String currency;
    private String status;

    protected Policy() {}

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getHolderId() { return holderId; }
    public ClaimType getType() { return type; }
    public Market getMarket() { return market; }
    public BigDecimal getCoverageLimit() { return coverageLimit; }
    public String getCurrency() { return currency; }
}
