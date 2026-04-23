package com.example.merchantlimit.application.usecase;

import java.math.BigDecimal;

public interface UpdateCancelLimitUseCase {
    Result execute(long merchantId, BigDecimal newLimit, String reason);

    record Result(long merchantId, BigDecimal dailyLimit) {}
}
