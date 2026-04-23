package com.example.riskmanagement.application.usecase;

import java.math.BigDecimal;

public interface CompensateUseCase {
    record Command(String cancelRequestId, long merchantId, BigDecimal restoreAmount) {}
    record Result(String cancelRequestId, boolean restored, String reason) {}
    Result execute(Command command);
}
