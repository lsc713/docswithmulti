package com.example.riskmanagement.domain.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class CancelLimitExceededException extends BusinessException {

    private final BigDecimal dailyLimit;
    private final BigDecimal usedAmount;
    private final BigDecimal requestAmount;

    public CancelLimitExceededException(
        BigDecimal dailyLimit, BigDecimal usedAmount, BigDecimal requestAmount) {
        super(ErrorCode.CANCEL_LIMIT_EXCEEDED);
        this.dailyLimit = dailyLimit;
        this.usedAmount = usedAmount;
        this.requestAmount = requestAmount;
    }
}
