package com.example.merchantlimit.presentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdateLimitRequest(
    @NotNull @DecimalMin("1") BigDecimal dailyLimit,
    String reason
) {}
