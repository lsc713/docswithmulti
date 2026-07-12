package com.example.riskmanagement.application.exception;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

/**
 * 보상 요청의 {@code merchantId} 가 차감 이력의 {@code merchantId} 와 불일치.
 * 요청 자체가 이력과 모순 → 재시도해도 소용없는 <b>호출자 잘못</b> → 400.
 * raw {@code IllegalArgumentException}(→500) 대신 예외 계층으로 일관 처리.
 */
public class CompensationMerchantMismatchException extends BusinessException {
    public CompensationMerchantMismatchException(long requestMerchantId, long historyMerchantId) {
        super(ErrorCode.COMPENSATION_MERCHANT_MISMATCH,
            "merchantId 불일치: 요청=" + requestMerchantId + ", 이력=" + historyMerchantId);
    }
}
