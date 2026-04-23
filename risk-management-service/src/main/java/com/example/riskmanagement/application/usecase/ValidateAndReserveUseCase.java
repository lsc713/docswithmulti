package com.example.riskmanagement.application.usecase;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ValidateAndReserveUseCase {
    record Command(long merchantId, String cancelRequestId, BigDecimal cancelAmount, LocalDate kstDate) {}
    record Result(long merchantId, BigDecimal dailyLimit, BigDecimal usedAmount, BigDecimal remainingLimit) {}
    Result execute(Command command);
}
