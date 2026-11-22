package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.domain.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID>, JpaSpecificationExecutor<Claim> {
    List<Claim> findByClaimantIdOrderByCreatedAtDesc(UUID claimantId);
}
