package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;

import java.math.BigDecimal;

public record ValidateAndReserveResponse(
    long merchantId,
    BigDecimal dailyLimit,
    BigDecimal usedAmount,
    BigDecimal remainingLimit
) {
    public static ValidateAndReserveResponse from(ValidateAndReserveUseCase.Result result) {
        return new ValidateAndReserveResponse(
            result.merchantId(), result.dailyLimit(), result.usedAmount(), result.remainingLimit());
    }
}
