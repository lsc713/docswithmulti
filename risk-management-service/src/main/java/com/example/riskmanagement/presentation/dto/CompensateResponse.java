package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.CompensateUseCase;

public record CompensateResponse(String cancelRequestId, boolean restored, String reason) {
    public static CompensateResponse from(CompensateUseCase.Result result) {
        return new CompensateResponse(result.cancelRequestId(), result.restored(), result.reason());
    }
}
