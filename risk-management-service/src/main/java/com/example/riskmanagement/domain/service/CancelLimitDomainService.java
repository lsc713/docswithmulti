package com.example.riskmanagement.domain.service;

import com.example.riskmanagement.application.exception.MerchantCancelLimitExceededException;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;

import java.math.BigDecimal;

public class CancelLimitDomainService {

    public void validateAndDeduct(MerchantCancelUsage usage, BigDecimal cancelAmount) {
        if (usage.remaining().compareTo(cancelAmount) < 0) {
            throw new MerchantCancelLimitExceededException(
                usage.getDailyLimit(), usage.getUsedAmount(), cancelAmount);
        }
        usage.deduct(cancelAmount);
    }

    public void applyCompensation(MerchantCancelUsage usage, BigDecimal restoreAmount) {
        usage.restore(restoreAmount);
    }
}
