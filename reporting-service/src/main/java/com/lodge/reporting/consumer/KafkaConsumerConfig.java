package com.lodge.reporting.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    /**
     * One bad message must never wedge a partition (same lesson as the producer-side outbox).
     * Poison messages are recorded as dead letters immediately; transient failures (DB blip) retry twice first.
     * If even recording fails the offset stays uncommitted and the record is redelivered: nothing is dropped.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterRecorder recorder) {
        var handler = new DefaultErrorHandler(recorder, new FixedBackOff(500L, 2L));
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }
}
