package com.claimdesk.reporting;

import com.claimdesk.reporting.consumer.DeadLetterRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetterRecorderTest {
    static class FakeJdbc extends JdbcTemplate {
        final List<Object[]> calls = new ArrayList<>();
        RuntimeException failFirstWith;
        @Override public int update(String sql, Object... args) {
            calls.add(args);
            if (calls.size() == 1 && failFirstWith != null) throw failFirstWith;
            return 1;
        }
    }

    final ConsumerRecord<String, String> rec = new ConsumerRecord<>("claims.events", 1, 42L, "k".repeat(5000), "bad\u0000payload");
    final Exception boom = new IllegalStateException("parse failed");

    @Test
    void sanitisesNulAndBoundsFieldsBeforeInserting() {
        var jdbc = new FakeJdbc();
        new DeadLetterRecorder(jdbc, new SimpleMeterRegistry()).accept(rec, boom);
        assertThat(jdbc.calls).hasSize(1);
        var args = jdbc.calls.get(0);
        assertThat((String) args[4]).hasSize(2000);
        assertThat((String) args[5]).doesNotContain("\u0000").contains("bad�payload");
    }

    @Test
    void fallsBackToCoordinatesOnlyWhenTheDatabaseRejectsTheDataItself() {
        var jdbc = new FakeJdbc();
        jdbc.failFirstWith = new DataIntegrityViolationException("value too long");
        new DeadLetterRecorder(jdbc, new SimpleMeterRegistry()).accept(rec, boom);
        assertThat(jdbc.calls).hasSize(2);
        var minimal = jdbc.calls.get(1);
        assertThat(minimal[4]).isNull();
        assertThat(minimal[5]).isNull();
        assertThat((String) minimal[6]).startsWith("Unrecordable message");
    }

    @Test
    void databaseOutagesAreNeverSwallowedSoTheRecordIsRedelivered() {
        var jdbc = new FakeJdbc();
        jdbc.failFirstWith = new CannotGetJdbcConnectionException("db down");
        assertThatThrownBy(() -> new DeadLetterRecorder(jdbc, new SimpleMeterRegistry()).accept(rec, boom))
                .isInstanceOf(CannotGetJdbcConnectionException.class);
        assertThat(jdbc.calls).hasSize(1);
    }

    @Test
    void dataErrorsMappedToResourceFailureAreStillTreatedAsBadData() {
        var jdbc = new FakeJdbc();
        jdbc.failFirstWith = new DataAccessResourceFailureException("null character not permitted",
                new java.sql.SQLException("null character not permitted", "22P05"));
        new DeadLetterRecorder(jdbc, new SimpleMeterRegistry()).accept(rec, boom);
        assertThat(jdbc.calls).as("fell back instead of wedging").hasSize(2);
    }

    @Test
    void connectionLossWithSqlStateIsRethrown() {
        var jdbc = new FakeJdbc();
        jdbc.failFirstWith = new DataAccessResourceFailureException("connection refused",
                new java.sql.SQLException("connection refused", "08001"));
        assertThatThrownBy(() -> new DeadLetterRecorder(jdbc, new SimpleMeterRegistry()).accept(rec, boom))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
