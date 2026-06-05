package com.example.payment.common.exception.application;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 가맹점 일일 취소한도 초과
 *
 * error-catalog.md: MERCHANT_CANCEL_LIMIT_EXCEEDED (422)
 * domain-rules.md 3-3: 한도 초과 처리
 * 잔여 한도보다 요청 금액이 크면 전액 거부한다.
 */
public class MerchantCancelLimitExceededException extends BusinessException {

    private final BigDecimal requestedAmount;
    private final BigDecimal remainingLimit;
    private final BigDecimal dailyLimit;

    public MerchantCancelLimitExceededException(
        BigDecimal requestedAmount,
        BigDecimal remainingLimit,
        BigDecimal dailyLimit
    ) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_EXCEEDED, "가맹점 일일 취소한도를 초과했습니다.");
        this.requestedAmount = requestedAmount;
        this.remainingLimit = remainingLimit;
        this.dailyLimit = dailyLimit;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getRemainingLimit() {
        return remainingLimit;
    }

    public BigDecimal getDailyLimit() {
        return dailyLimit;
    }
}
