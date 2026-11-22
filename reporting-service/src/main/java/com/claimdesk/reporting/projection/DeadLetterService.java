package com.claimdesk.reporting.projection;

import com.claimdesk.reporting.consumer.EventParser;
import com.claimdesk.reporting.consumer.PoisonEventException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator tooling for messages the consumer gave up on. Re-drive applies the stored payload straight through the
 * same parser and idempotent projection used by the listener (no Kafka round trip), so it is safe to repeat and
 * cannot move a claim backwards.
 */
@Service
public class DeadLetterService {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private final JdbcTemplate jdbc;
    private final EventParser parser;
    private final ProjectionService projection;

    public DeadLetterService(JdbcTemplate jdbc, EventParser parser, ProjectionService projection, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.projection = projection;
        Gauge.builder("reporting.dead_letters.unresolved", this, DeadLetterService::unresolvedCount)
                .description("Dead letters awaiting operator action").register(registry);
    }

    public record Row(UUID id, String topic, int partition, long offset, String key, String payload, String error,
                      Instant receivedAt, int redriveAttempts, String lastError) {}
    public record RedriveResult(UUID id, boolean redriven, String error) {}

    public double unresolvedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM dead_letter WHERE resolved_at IS NULL", Long.class);
    }

    public List<Row> listUnresolved() {
        return jdbc.query("""
                SELECT id, topic, kafka_partition, kafka_offset, msg_key, left(payload, 2000) AS payload, error,
                       received_at, redrive_attempts, last_error
                FROM dead_letter WHERE resolved_at IS NULL ORDER BY received_at LIMIT 100""",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getInt("kafka_partition"),
                        rs.getLong("kafka_offset"), rs.getString("msg_key"), rs.getString("payload"), rs.getString("error"),
                        rs.getTimestamp("received_at").toInstant(), rs.getInt("redrive_attempts"), rs.getString("last_error")));
    }

    public RedriveResult redrive(UUID id, UUID actorOrNull) {
        var payload = loadUnresolvedPayload(id);
        try {
            projection.apply(parser.parse(payload));
        } catch (PoisonEventException e) {
            jdbc.update("UPDATE dead_letter SET redrive_attempts = redrive_attempts + 1, last_error = ? WHERE id = ?",
                    trim(e.getMessage()), id);
            return new RedriveResult(id, false, e.getMessage());
        }
        jdbc.update("UPDATE dead_letter SET resolved_at = now(), resolution = 'REDRIVEN' WHERE id = ? AND resolved_at IS NULL", id);
        log.warn("Dead letter {} re-driven by {}", id, actorOrNull);
        return new RedriveResult(id, true, null);
    }

    public List<RedriveResult> redriveAll(UUID actor) {
        return listUnresolved().stream().map(r -> redrive(r.id(), actor)).toList();
    }

    public void discard(UUID id, UUID actor) {
        loadUnresolvedPayload(id);
        jdbc.update("UPDATE dead_letter SET resolved_at = now(), resolution = 'DISCARDED' WHERE id = ? AND resolved_at IS NULL", id);
        log.warn("Dead letter {} discarded by {}", id, actor);
    }

    private String loadUnresolvedPayload(UUID id) {
        var rows = jdbc.query("SELECT payload, resolved_at FROM dead_letter WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString("payload"), rs.getTimestamp("resolved_at")}, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dead letter " + id);
        if (rows.get(0)[1] != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dead letter already resolved");
        return (String) rows.get(0)[0];
    }

    private static String trim(String s) { return s == null ? null : s.substring(0, Math.min(s.length(), 1000)); }
}
