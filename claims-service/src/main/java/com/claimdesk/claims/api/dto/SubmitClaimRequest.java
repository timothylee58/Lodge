package com.claimdesk.claims.api.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public record SubmitClaimRequest(@NotNull UUID policyId, @NotNull @PastOrPresent LocalDate incidentDate,
                                 @NotBlank @Size(max = 4000) String description) {}
