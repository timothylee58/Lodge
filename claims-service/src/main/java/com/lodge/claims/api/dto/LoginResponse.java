package com.lodge.claims.api.dto;

import java.util.UUID;

public record LoginResponse(String token, long expiresInSeconds, UUID userId, String name, String role) {}
