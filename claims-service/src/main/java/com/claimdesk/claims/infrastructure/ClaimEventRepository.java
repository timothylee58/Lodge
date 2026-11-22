package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.domain.ClaimEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ClaimEventRepository extends JpaRepository<ClaimEvent, UUID> {
    List<ClaimEvent> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
