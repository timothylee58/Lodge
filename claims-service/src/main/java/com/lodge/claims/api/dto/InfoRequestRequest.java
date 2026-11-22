package com.lodge.claims.api.dto;

import jakarta.validation.constraints.*;

public record InfoRequestRequest(@NotBlank @Size(max = 2000) String question) {}
