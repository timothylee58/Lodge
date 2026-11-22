package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.domain.InfoRequest;
import com.claimdesk.claims.domain.InfoRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InfoRequestRepository extends JpaRepository<InfoRequest, UUID> {
    Optional<InfoRequest> findFirstByClaimIdAndStatus(UUID claimId, InfoRequestStatus status);
    List<InfoRequest> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
