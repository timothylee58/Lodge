package com.claimdesk.reporting.consumer;

import com.claimdesk.reporting.projection.ProjectionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ClaimEventsListener {
    private final ObjectMapper json;
    private final ProjectionService projection;

    public ClaimEventsListener(ObjectMapper json, ProjectionService projection) {
        this.json = json;
        this.projection = projection;
    }

    @KafkaListener(topics = "${reporting.topic}")
    public void onEvent(String payload) {
        ClaimEventMessage msg;
        try {
            msg = json.readValue(payload, ClaimEventMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PoisonEventException("Unparseable event", e);
        }
        if (msg == null) throw new PoisonEventException("Empty event");
        msg.requireValid();
        projection.apply(msg); // DB failures propagate -> retried, then dead-lettered
    }
}
