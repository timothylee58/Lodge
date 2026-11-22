package com.claimdesk.claims.infrastructure;

import com.claimdesk.claims.domain.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    List<Policy> findByHolderId(UUID holderId);
}
