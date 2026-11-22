package com.lodge.reporting.projection;

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
    public record CurrencyTotal(String currency, long openClaims, BigDecimal exposure) {}
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

    /** Open exposure summed per currency across markets. Never mixes currencies (no FX). */
    public List<CurrencyTotal> exposureByCurrency() {
        return jdbc.query("SELECT currency, count(*) AS n, COALESCE(sum(reserve_amount), 0) AS exposure " +
                        "FROM claim_projection WHERE " + OPEN + " GROUP BY currency ORDER BY currency",
                (rs, i) -> new CurrencyTotal(rs.getString("currency"), rs.getLong("n"), rs.getBigDecimal("exposure")));
    }

    /** Every officer ever seen on an assignment, including those currently at 0 open claims. */
    public List<WorkloadRow> workload() {
        return jdbc.query("""
                SELECT o.officer_id, o.name,
                       count(c.claim_id) FILTER (WHERE c.status = 'UNDER_REVIEW') AS under_review,
                       count(c.claim_id) FILTER (WHERE c.status = 'PENDING_INFO') AS pending_info,
                       count(c.claim_id) FILTER (WHERE c.status = 'APPROVED')     AS approved
                FROM known_officer o
                LEFT JOIN claim_projection c ON c.assignee_id = o.officer_id
                GROUP BY o.officer_id, o.name ORDER BY o.name
                """,
                (rs, i) -> new WorkloadRow(rs.getObject("officer_id", UUID.class), rs.getString("name"),
                        rs.getLong("under_review"), rs.getLong("pending_info"), rs.getLong("approved")));
    }

    public long unassigned() {
        return jdbc.queryForObject("SELECT count(*) FROM claim_projection WHERE assignee_id IS NULL AND status = 'SUBMITTED'", Long.class);
    }
}
