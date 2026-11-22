package com.lodge.claims.application;

import com.lodge.claims.domain.BusinessConflictException;
import com.lodge.claims.infrastructure.OutboxEvent;
import com.lodge.claims.infrastructure.OutboxRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operator tooling for dead-lettered outbox rows. Safe to re-drive: consumers dedupe by eventId and ignore stale versions. */
@Service
public class OutboxOpsService {
    private static final Logger log = LoggerFactory.getLogger(OutboxOpsService.class);
    private final OutboxRepository outbox;

    public OutboxOpsService(OutboxRepository outbox) { this.outbox = outbox; }

    public record DeadRow(UUID id, UUID aggregateId, String eventType, int attempts, String lastError,
                          Instant createdAt, Instant deadAt) {}

    @Transactional(readOnly = true)
    public List<DeadRow> listDead() {
        return outbox.findTop100ByDeadAtIsNotNullOrderByCreatedAtAsc().stream().map(OutboxOpsService::toRow).toList();
    }

    @Transactional
    public DeadRow redrive(UUID id, UUID actor) {
        var e = outbox.findById(id).orElseThrow(() -> new EntityNotFoundException("Outbox event " + id));
        if (!e.isDead()) throw new BusinessConflictException("Outbox event is not dead-lettered");
        e.revive();
        log.warn("Outbox event {} ({}) re-driven by {}", id, e.getEventType(), actor);
        return toRow(e);
    }

    /** Re-queues up to 100 dead rows per call (oldest first). */
    @Transactional
    public int redriveAll(UUID actor) {
        var dead = outbox.findTop100ByDeadAtIsNotNullOrderByCreatedAtAsc();
        dead.forEach(OutboxEvent::revive);
        if (!dead.isEmpty()) log.warn("{} dead outbox events re-driven by {}", dead.size(), actor);
        return dead.size();
    }

    private static DeadRow toRow(OutboxEvent e) {
        return new DeadRow(e.getId(), e.getAggregateId(), e.getEventType(), e.getAttempts(), e.getLastError(),
                e.getCreatedAt(), e.getDeadAt());
    }
}
