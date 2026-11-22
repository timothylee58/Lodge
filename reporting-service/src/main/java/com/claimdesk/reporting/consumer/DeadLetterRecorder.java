package com.claimdesk.reporting.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Last stop for a record the listener could not process. If recording itself fails (DB down) the error handler
 * keeps the offset uncommitted and retries, so a message is never silently lost.
 */
@Component
public class DeadLetterRecorder implements ConsumerRecordRecoverer {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecorder.class);
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
        var error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        int n = jdbc.update("""
                INSERT INTO dead_letter (id, topic, kafka_partition, kafka_offset, msg_key, payload, error)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (topic, kafka_partition, kafka_offset) DO NOTHING""",
                UUID.randomUUID(), rec.topic(), rec.partition(), rec.offset(),
                rec.key() == null ? null : rec.key().toString(),
                rec.value() == null ? null : rec.value().toString(),
                error.length() > 1000 ? error.substring(0, 1000) : error);
        if (n > 0) {
            recorded.increment();
            log.error("Dead-lettered {}-{}@{}: {}", rec.topic(), rec.partition(), rec.offset(), error);
        }
    }
}
