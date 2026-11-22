package com.lodge.reporting.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps processed_event bounded. Safe because it only affects exact-duplicate detection: even a pruned id that is
 * redelivered later is a no-op, since the projection upsert requires a strictly newer claim version.
 * Keep retention >= the topic's retention so a full replay never sees pruned ids as new (and even then it is harmless).
 */
@Component
public class ProcessedEventPruner {
    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPruner.class);
    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public ProcessedEventPruner(JdbcTemplate jdbc, @Value("${reporting.processed-event-retention-days:7}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${reporting.prune-cron:0 17 3 * * *}")
    public int prune() {
        int n = jdbc.update("DELETE FROM processed_event WHERE processed_at < now() - make_interval(days => ?)", retentionDays);
        if (n > 0) log.info("Pruned {} processed_event rows older than {} days", n, retentionDays);
        return n;
    }
}
