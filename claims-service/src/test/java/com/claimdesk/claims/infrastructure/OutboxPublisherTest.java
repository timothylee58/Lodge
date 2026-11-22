package com.claimdesk.claims.infrastructure;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {
    OutboxRepository repo = mock(OutboxRepository.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    OutboxPublisher publisher = new OutboxPublisher(repo, kafka, 3);
    List<OutboxEvent> rows = new ArrayList<>();
    OutboxEvent poison, healthy;

    @BeforeEach
    void setUp() {
        poison = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "CLAIM_ASSIGNED", "claims.events", "poison");
        healthy = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "CLAIM_ASSIGNED", "claims.events", "ok");
        rows.addAll(List.of(poison, healthy));
        // Same visibility rule as the real query: skip published and dead rows, oldest first.
        when(repo.findTop100ByPublishedAtIsNullAndDeadAtIsNullOrderByCreatedAtAsc()).thenAnswer(i ->
                rows.stream().filter(e -> e.getPublishedAt() == null && !e.isDead()).toList());
        when(repo.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void sendFailsFor(String payload, Throwable error) {
        when(kafka.send(any(Message.class))).thenAnswer(i -> {
            var m = (Message<String>) i.getArgument(0);
            return m.getPayload().equals(payload)
                    ? CompletableFuture.<SendResult<String, String>>failedFuture(error)
                    : CompletableFuture.completedFuture(mock(SendResult.class));
        });
    }

    @Test
    void poisonRowIsDeadLetteredAfterMaxAttemptsAndStopsBlockingTheQueue() {
        sendFailsFor("poison", new RecordTooLargeException("too big"));

        publisher.publish();
        publisher.publish();
        assertThat(poison.getAttempts()).isEqualTo(2);
        assertThat(poison.isDead()).isFalse();
        assertThat(healthy.getPublishedAt()).as("order preserved while the row may still recover").isNull();

        publisher.publish(); // third strike
        assertThat(poison.isDead()).isTrue();
        assertThat(poison.getLastError()).contains("too big");
        assertThat(healthy.getPublishedAt()).as("unblocked in the same tick").isNotNull();
    }

    @Test
    void brokerOutageNeverBurnsAttemptsOrDeadLettersHealthyRows() {
        sendFailsFor("poison", new TimeoutException("broker down"));

        for (int i = 0; i < 10; i++) publisher.publish();

        assertThat(poison.getAttempts()).isZero();
        assertThat(poison.isDead()).isFalse();
        assertThat(healthy.getPublishedAt()).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void retriableErrorsClassifiedCorrectly() {
        assertThat(OutboxPublisher.isRetriable(new TimeoutException("t"))).isTrue();
        assertThat(OutboxPublisher.isRetriable(new java.util.concurrent.TimeoutException())).isTrue();
        assertThat(OutboxPublisher.isRetriable(new RecordTooLargeException("x"))).isFalse();
        assertThat(OutboxPublisher.isRetriable(new IllegalArgumentException("x"))).isFalse();
    }
}
