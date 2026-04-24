package com.example.riskmanagement.application.usecase;

import java.math.BigDecimal;

public interface CheckChargeUseCase {
    record Result(String cancelRequestId, boolean charged, Long merchantId, BigDecimal cancelAmount) {}
    Result execute(String cancelRequestId);
}
