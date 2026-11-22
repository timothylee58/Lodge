package com.claimdesk.claims.api.dto;

import jakarta.validation.constraints.*;

public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}
