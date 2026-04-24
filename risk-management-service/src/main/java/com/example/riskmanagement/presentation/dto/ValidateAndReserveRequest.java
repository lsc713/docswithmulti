package com.example.riskmanagement.presentation.dto;

import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ValidateAndReserveRequest(
    @NotNull Long merchantId,
    @NotBlank String cancelRequestId,
    @NotNull @DecimalMin("0.01") BigDecimal cancelAmount,
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "kstDate는 yyyy-MM-dd 형식이어야 합니다.")
    @NotBlank String kstDate
) {
    public ValidateAndReserveUseCase.Command toCommand() {
        return new ValidateAndReserveUseCase.Command(
            merchantId, cancelRequestId, cancelAmount, LocalDate.parse(kstDate));
    }
}
