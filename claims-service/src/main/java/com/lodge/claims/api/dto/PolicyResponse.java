package com.lodge.claims.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PolicyResponse(UUID id, String policyNumber, String type, String market,
                             BigDecimal coverageLimit, String currency) {}
