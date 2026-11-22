package com.claimdesk.reporting.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ReportQueries {
    /** "Open" = still carries exposure. APPROVED is open until it is settled. */
    private static final String OPEN = "status NOT IN ('SETTLED', 'REJECTED')";
    private final JdbcTemplate jdbc;

    public ReportQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ExposureRow(String market, String currency, String type, long openClaims, BigDecimal exposure) {}
    public record StatusRow(String market, String status, long claims) {}
    public record WorkloadRow(UUID assigneeId, String assigneeName, long underReview, long pendingInfo, long approved) {
        public long openClaims() { return underReview + pendingInfo + approved; }
    }

    /** Timestamp of the newest event reflected in the read model (null if empty). */
    public Instant lastEventAt() {
        Timestamp t = jdbc.queryForObject("SELECT max(occurred_at) FROM claim_projection", Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    public List<ExposureRow> exposure() {
        return jdbc.query("SELECT market, currency, type, count(*) AS n, COALESCE(sum(reserve_amount), 0) AS exposure " +
                        "FROM claim_projection WHERE " + OPEN + " GROUP BY market, currency, type ORDER BY market, type",
                (rs, i) -> new ExposureRow(rs.getString("market"), rs.getString("currency"), rs.getString("type"),
                        rs.getLong("n"), rs.getBigDecimal("exposure")));
    }

    public List<StatusRow> claimsByStatus() {
        return jdbc.query("SELECT market, status, count(*) AS n FROM claim_projection GROUP BY market, status ORDER BY market, status",
                (rs, i) -> new StatusRow(rs.getString("market"), rs.getString("status"), rs.getLong("n")));
    }

    public List<WorkloadRow> workload() {
        return jdbc.query("""
                SELECT assignee_id, max(assignee_name) AS name,
                       count(*) FILTER (WHERE status = 'UNDER_REVIEW') AS under_review,
                       count(*) FILTER (WHERE status = 'PENDING_INFO') AS pending_info,
                       count(*) FILTER (WHERE status = 'APPROVED')     AS approved
                FROM claim_projection
                WHERE assignee_id IS NOT NULL AND status IN ('UNDER_REVIEW', 'PENDING_INFO', 'APPROVED')
                GROUP BY assignee_id ORDER BY name
                """,
                (rs, i) -> new WorkloadRow(rs.getObject("assignee_id", UUID.class), rs.getString("name"),
                        rs.getLong("under_review"), rs.getLong("pending_info"), rs.getLong("approved")));
    }

    public long unassigned() {
        return jdbc.queryForObject("SELECT count(*) FROM claim_projection WHERE assignee_id IS NULL AND status = 'SUBMITTED'", Long.class);
    }
}
