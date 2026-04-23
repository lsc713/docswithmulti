package com.example.payment.application.interfaces;

import com.example.payment.application.dto.RiskReserveResult;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface RiskManagementPort {
    /** 한도 검증 + 선차감. 422 한도초과 시 MerchantCancelLimitExceededException throw. */
    RiskReserveResult validateAndReserve(long merchantId, long cancelRequestId,
                                          BigDecimal cancelAmount, LocalDate kstDate);

    /** 보상 트랜잭션. 멱등 (이미 보상됐으면 no-op). */
    void compensate(long cancelRequestId, long merchantId, BigDecimal restoreAmount);
}
