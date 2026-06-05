package com.example.riskmanagement.common.exception.domain;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class MerchantCancelLimitExceededException extends BusinessException {

    private final BigDecimal dailyLimit;
    private final BigDecimal usedAmount;
    private final BigDecimal requestAmount;

    public MerchantCancelLimitExceededException(BigDecimal dailyLimit, BigDecimal usedAmount, BigDecimal requestAmount) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_EXCEEDED);
        this.dailyLimit = dailyLimit;
        this.usedAmount = usedAmount;
        this.requestAmount = requestAmount;
    }
}
