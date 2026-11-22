package com.claimdesk.claims.api.dto;

import java.time.Instant;
import java.util.UUID;

public record InfoRequestView(UUID id, String question, String response, String status,
                              Instant createdAt, Instant answeredAt) {}
