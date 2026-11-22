package com.lodge.reporting.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Last stop for a record the listener could not process. Two rules keep it from becoming the thing that wedges a
 * partition:
 *  1. Anything about the MESSAGE that the database cannot store (NUL characters, oversize fields) is sanitised, and if
 *     the insert is still rejected as bad data we fall back to a minimal row (coordinates + error, no key/payload).
 *  2. Anything about the DATABASE (down, timeout) is NOT swallowed: the exception propagates, the offset stays
 *     uncommitted and the record is redelivered, so nothing is silently dropped.
 */
@Component
public class DeadLetterRecorder implements ConsumerRecordRecoverer {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecorder.class);
    private static final String INSERT = """
            INSERT INTO dead_letter (id, topic, kafka_partition, kafka_offset, msg_key, payload, error)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (topic, kafka_partition, kafka_offset) DO NOTHING""";
    private final JdbcTemplate jdbc;
    private final Counter recorded;

    public DeadLetterRecorder(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.recorded = Counter.builder("reporting.dead_letters.recorded")
                .description("Messages moved to dead_letter since startup").register(registry);
    }

    @Override
    public void accept(ConsumerRecord<?, ?> rec, Exception ex) {
        var cause = NestedExceptionUtils.getMostSpecificCause(ex);
        var error = clean(cause.getClass().getSimpleName() + ": " + cause.getMessage(), 1000);
        var topic = clean(rec.topic(), 500);
        int n;
        try {
            n = jdbc.update(INSERT, UUID.randomUUID(), topic, rec.partition(), rec.offset(),
                    rec.key() == null ? null : clean(rec.key().toString(), 2000),
                    rec.value() == null ? null : clean(rec.value().toString(), Integer.MAX_VALUE), error);
        } catch (DataAccessException e) {
            if (!isBadData(e)) throw e; // DB down / timeout: let the record be redelivered
            log.error("Dead letter {}-{}@{} could not be stored in full ({}); storing coordinates only",
                    rec.topic(), rec.partition(), rec.offset(), NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            n = jdbc.update(INSERT, UUID.randomUUID(), topic, rec.partition(), rec.offset(), null, null,
                    clean("Unrecordable message (" + NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName()
                            + "). Original error: " + error, 1000));
        }
        if (n > 0) {
            recorded.increment();
            log.error("Dead-lettered {}-{}@{}: {}", rec.topic(), rec.partition(), rec.offset(), error);
        }
    }

    /**
     * SQLState class 22 (data exception: too long, bad encoding, NUL) or 23 (integrity) = the data is the problem.
     * Decided by SQLState, not exception type: Spring maps e.g. 22P05 to DataAccessResourceFailureException, the same
     * family it uses for "database unreachable".
     */
    static boolean isBadData(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState().startsWith("22") || sql.getSQLState().startsWith("23");
            }
        }
        return e instanceof DataIntegrityViolationException;
    }

    /** Postgres text cannot contain NUL (0x00). Replace it, and bound the length. */
    static String clean(String s, int max) {
        if (s == null) return null;
        var t = s.indexOf('\u0000') >= 0 ? s.replace('\u0000', '�') : s;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
