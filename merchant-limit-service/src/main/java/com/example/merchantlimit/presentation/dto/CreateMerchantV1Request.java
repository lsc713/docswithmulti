package com.example.merchantlimit.presentation.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateMerchantV1Request(
    @NotBlank @Size(max = 64) String merchantKey,
    @NotBlank @Size(max = 255) String name,
    @Min(1) @Max(365) int cancelPeriodDays,
    @NotNull @Positive BigDecimal dailyLimit
) {}
