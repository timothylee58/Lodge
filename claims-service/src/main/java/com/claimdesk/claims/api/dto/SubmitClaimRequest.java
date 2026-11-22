package com.claimdesk.claims.api.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public record SubmitClaimRequest(@NotNull UUID policyId, @NotNull LocalDate incidentDate /* future check is per market, in the service */,
                                 @NotBlank @Size(max = 4000) String description) {}
