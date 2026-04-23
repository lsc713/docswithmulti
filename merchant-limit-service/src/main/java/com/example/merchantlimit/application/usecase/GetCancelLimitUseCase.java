package com.example.merchantlimit.application.usecase;

import java.math.BigDecimal;

public interface GetCancelLimitUseCase {
    Result execute(long merchantId);

    record Result(long merchantId, BigDecimal dailyLimit, String merchantStatus) {}
}
