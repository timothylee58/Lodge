package com.claimdesk.claims.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ReserveRequest(@NotNull @PositiveOrZero BigDecimal reserveAmount) {}
