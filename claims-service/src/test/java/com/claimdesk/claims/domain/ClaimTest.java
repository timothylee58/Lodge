package com.claimdesk.claims.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ClaimTest {
    private final UUID officer = UUID.randomUUID();

    private Claim newClaim() {
        return Claim.submit("CLM-1000", UUID.randomUUID(), UUID.randomUUID(), ClaimType.MOTOR,
                Market.MY, LocalDate.now(), "Rear-ended at junction", "MYR");
    }

    @Test
    void happyPathToSettlementClearsExposure() {
        var c = newClaim();
        c.assignTo(officer);
        c.approve(new BigDecimal("8000"));
        assertThat(c.getReserveAmount()).isEqualByComparingTo("8000");

        c.settle(new BigDecimal("7500"), new BigDecimal("50000"));
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(c.getReserveAmount()).isZero();
    }

    @Test
    void cannotApproveUnassignedClaim() {
        assertThatThrownBy(() -> newClaim().approve(BigDecimal.TEN))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void cannotSettleAboveCoverage() {
        var c = newClaim();
        c.assignTo(officer);
        c.approve(BigDecimal.TEN);
        assertThatThrownBy(() -> c.settle(new BigDecimal("60000"), new BigDecimal("50000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void infoRequestLoopReturnsToReview() {
        var c = newClaim();
        c.assignTo(officer);
        c.requestInfo();
        c.infoProvided();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    }

    @Test
    void terminalStatesAllowNothing() {
        assertThat(ClaimStatus.SETTLED.allowedNext()).isEmpty();
        assertThat(ClaimStatus.REJECTED.allowedNext()).isEmpty();
    }
}
