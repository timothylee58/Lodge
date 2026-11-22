package com.lodge.reporting.consumer;

import com.lodge.reporting.projection.ProjectionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ClaimEventsListener {
    private final EventParser parser;
    private final ProjectionService projection;

    public ClaimEventsListener(EventParser parser, ProjectionService projection) {
        this.parser = parser;
        this.projection = projection;
    }

    @KafkaListener(topics = "${reporting.topic}")
    public void onEvent(String payload) {
        projection.apply(parser.parse(payload)); // DB failures propagate -> retried, then recorded as a dead letter
    }
}
