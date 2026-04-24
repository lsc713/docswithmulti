package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.CheckChargeUseCase;

import java.math.BigDecimal;

public record CheckChargeResponse(
    String cancelRequestId, boolean charged, Long merchantId, BigDecimal cancelAmount
) {
    public static CheckChargeResponse from(CheckChargeUseCase.Result result) {
        return new CheckChargeResponse(
            result.cancelRequestId(), result.charged(), result.merchantId(), result.cancelAmount());
    }
}
