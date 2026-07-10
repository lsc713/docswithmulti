package com.example.riskmanagement.domain.service;

import com.example.riskmanagement.domain.exception.MerchantCancelLimitExceededException;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;

import java.math.BigDecimal;

public class CancelLimitDomainService {

    /**
     * 차감 허용 규칙의 <b>원본</b>. {@code used + amount <= dailyLimit} 이면 허용.
     * 인프라의 원자 조건부 UPDATE(WHERE used_amount + :amt <= daily_limit)는
     * 이 규칙을 그대로 옮긴 것이며, 동치는 테스트로 고정한다.
     */
    public boolean canDeduct(BigDecimal dailyLimit, BigDecimal usedAmount, BigDecimal amount) {
        return usedAmount.add(amount).compareTo(dailyLimit) <= 0;
    }

    public void validateAndDeduct(MerchantCancelUsage usage, BigDecimal cancelAmount) {
        if (!canDeduct(usage.getDailyLimit(), usage.getUsedAmount(), cancelAmount)) {
            throw new MerchantCancelLimitExceededException(
                usage.getDailyLimit(), usage.getUsedAmount(), cancelAmount);
        }
        usage.deduct(cancelAmount);
    }

    public void applyCompensation(MerchantCancelUsage usage, BigDecimal restoreAmount) {
        usage.restore(restoreAmount);
    }
}
