package com.lodge.claims.api.dto;

import jakarta.validation.constraints.*;

public record InfoResponseRequest(@NotBlank @Size(max = 4000) String response) {}
