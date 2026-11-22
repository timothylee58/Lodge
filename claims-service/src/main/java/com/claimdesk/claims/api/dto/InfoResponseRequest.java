package com.claimdesk.claims.api.dto;

import jakarta.validation.constraints.*;

public record InfoResponseRequest(@NotBlank @Size(max = 4000) String response) {}
