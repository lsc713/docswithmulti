package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.CompensateUseCase;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CompensateRequest(
    @NotBlank String cancelRequestId,
    @NotNull Long merchantId,
    @NotNull @DecimalMin("0.01") BigDecimal restoreAmount
) {
    public CompensateUseCase.Command toCommand() {
        return new CompensateUseCase.Command(cancelRequestId, merchantId, restoreAmount);
    }
}
