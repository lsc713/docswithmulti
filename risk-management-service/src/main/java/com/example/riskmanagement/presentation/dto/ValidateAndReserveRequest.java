package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ValidateAndReserveRequest(
    @NotNull Long merchantId,
    @NotBlank String cancelRequestId,
    @NotNull @DecimalMin("0.01") BigDecimal cancelAmount,
    @NotBlank String kstDate
) {
    public ValidateAndReserveUseCase.Command toCommand() {
        return new ValidateAndReserveUseCase.Command(
            merchantId, cancelRequestId, cancelAmount, LocalDate.parse(kstDate));
    }
}
