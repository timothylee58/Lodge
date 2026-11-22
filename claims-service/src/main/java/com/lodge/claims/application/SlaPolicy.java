package com.lodge.claims.application;

import com.lodge.claims.domain.Claim;
import com.lodge.claims.domain.ClaimStatus;
import com.lodge.claims.domain.Sla;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Service-level targets per status (time allowed IN that status). Server-side so the UI, reports and any future
 * alert agree on one answer and one clock. The decision clock pauses while waiting on the claimant (see Claim). PENDING_INFO measures the claimant's response time: a breach there means
 * "chase the claimant", not "the officer is late".
 */
@Component
public class SlaPolicy {
    private final Map<ClaimStatus, Duration> targets = new EnumMap<>(ClaimStatus.class);
    private final Clock clock;

    public SlaPolicy(Clock clock,
                     @Value("${lodge.sla.submitted-hours:24}") long submitted,
                     @Value("${lodge.sla.under-review-hours:120}") long review,
                     @Value("${lodge.sla.pending-info-hours:168}") long pendingInfo,
                     @Value("${lodge.sla.approved-hours:72}") long approved) {
        this.clock = clock;
        targets.put(ClaimStatus.SUBMITTED, Duration.ofHours(submitted));
        targets.put(ClaimStatus.UNDER_REVIEW, Duration.ofHours(review));
        targets.put(ClaimStatus.PENDING_INFO, Duration.ofHours(pendingInfo));
        targets.put(ClaimStatus.APPROVED, Duration.ofHours(approved));
    }

    public Sla assess(Claim c) { return Sla.assess(targets.get(c.getStatus()), c.getSlaStartedAt(), now()); }

    public Instant now() { return clock.instant(); }

    public long targetSeconds(ClaimStatus status) { return targets.get(status).toSeconds(); }
}
