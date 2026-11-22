package com.lodge.claims.api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ApproveRequest(@NotNull @PositiveOrZero BigDecimal reserveAmount) {}
