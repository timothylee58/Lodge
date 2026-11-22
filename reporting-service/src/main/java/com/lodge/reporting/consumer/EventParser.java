package com.lodge.reporting.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Single place that turns a raw payload into a valid message; used by the listener and by operator re-drive. */
@Component
public class EventParser {
    private final ObjectMapper json;

    public EventParser(ObjectMapper json) { this.json = json; }

    public ClaimEventMessage parse(String payload) {
        if (payload == null) throw new PoisonEventException("Empty event");
        ClaimEventMessage msg;
        try {
            msg = json.readValue(payload, ClaimEventMessage.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PoisonEventException("Unparseable event", e);
        }
        if (msg == null) throw new PoisonEventException("Empty event");
        msg.requireValid();
        return msg;
    }
}
