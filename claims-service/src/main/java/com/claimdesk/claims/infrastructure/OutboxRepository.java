package com.claimdesk.claims.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop100ByPublishedAtIsNullAndDeadAtIsNullOrderByCreatedAtAsc();

    List<OutboxEvent> findTop100ByDeadAtIsNotNullOrderByCreatedAtAsc();
    long countByDeadAtIsNotNull();
    long countByPublishedAtIsNullAndDeadAtIsNull();
    java.util.Optional<OutboxEvent> findFirstByPublishedAtIsNullAndDeadAtIsNullOrderByCreatedAtAsc();

    @Transactional
    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
