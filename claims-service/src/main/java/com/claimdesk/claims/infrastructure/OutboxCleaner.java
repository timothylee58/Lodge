package com.claimdesk.claims.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;

/**
 * Published outbox rows are only delivery bookkeeping (the audit trail is claim_event), so they are deleted after
 * a retention window. Dead-lettered and unpublished rows are NEVER deleted here: they need a human.
 */
@Component
public class OutboxCleaner {
    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);
    private final OutboxRepository outbox;
    private final Duration retention;

    public OutboxCleaner(OutboxRepository outbox, @Value("${claimdesk.outbox.retention-days:7}") int days) {
        this.outbox = outbox;
        this.retention = Duration.ofDays(days);
    }

    @Scheduled(cron = "${claimdesk.outbox.cleanup-cron:0 7 3 * * *}")
    public int prune() {
        int n = outbox.deletePublishedBefore(Instant.now().minus(retention));
        if (n > 0) log.info("Pruned {} published outbox rows older than {}", n, retention);
        return n;
    }
}
