package com.claimdesk.reporting.projection;

import com.claimdesk.reporting.consumer.ClaimEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;

@Service
public class ProjectionService {
    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);
    private final JdbcTemplate jdbc;

    public ProjectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Idempotent and order-tolerant, in ONE transaction:
     *  1. processed_event insert catches exact redeliveries (at-least-once).
     *  2. The upsert only overwrites when the incoming claim version is newer, so late/out-of-order
     *     events (replay, retries, DLT re-drive) can never move a claim backwards.
     * If step 2 throws, step 1 rolls back too, so the retry isn't mistaken for a duplicate.
     *
     * @return true if the projection changed
     */
    @Transactional
    public boolean apply(ClaimEventMessage m) {
        int fresh = jdbc.update("INSERT INTO processed_event (event_id) VALUES (?) ON CONFLICT DO NOTHING", m.eventId());
        if (fresh == 0) {
            log.debug("Duplicate event {} ignored", m.eventId());
            return false;
        }
        if (m.assigneeId() != null && m.assigneeName() != null) {
            // Roster for the workload report, so an officer who clears their queue still shows up with 0.
            jdbc.update("""
                    INSERT INTO known_officer (officer_id, name, last_seen) VALUES (?, ?, ?)
                    ON CONFLICT (officer_id) DO UPDATE SET name = EXCLUDED.name,
                        last_seen = GREATEST(known_officer.last_seen, EXCLUDED.last_seen)
                    """, m.assigneeId(), m.assigneeName(), Timestamp.from(m.occurredAt()));
        }
        int changed = jdbc.update("""
                INSERT INTO claim_projection (claim_id, claim_number, market, type, currency, status,
                                              reserve_amount, assignee_id, assignee_name, version, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (claim_id) DO UPDATE SET
                    status = EXCLUDED.status, reserve_amount = EXCLUDED.reserve_amount,
                    assignee_id = EXCLUDED.assignee_id, assignee_name = EXCLUDED.assignee_name,
                    version = EXCLUDED.version, occurred_at = EXCLUDED.occurred_at
                WHERE claim_projection.version < EXCLUDED.version
                """,
                m.claimId(), m.claimNumber(), m.market(), m.type(), m.currency(), m.status(),
                m.reserveOrZero(), m.assigneeId(), m.assigneeName(), m.version(), Timestamp.from(m.occurredAt()));
        if (changed == 0) log.debug("Stale event {} (claim {} v{}) ignored", m.eventId(), m.claimId(), m.version());
        return changed == 1;
    }
}
