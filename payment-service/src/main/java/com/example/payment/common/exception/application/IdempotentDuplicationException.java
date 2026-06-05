package com.example.payment.common.exception.application;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.CancelStatus;

/**
 * 멱등성 중복 요청
 *
 * error-catalog.md: IDEMPOTENT_DUPLICATION (409)
 * domain-rules.md 5-1: Idempotency-Key 유효 기간 24시간
 */
public class IdempotentDuplicationException extends BusinessException {

    private final String cancelRequestId;
    private final CancelStatus originalStatus;

    public IdempotentDuplicationException(String cancelRequestId, CancelStatus originalStatus) {
        super(ErrorCode.IDEMPOTENT_DUPLICATION, "이미 처리된 요청입니다.");
        this.cancelRequestId = cancelRequestId;
        this.originalStatus = originalStatus;
    }

    public String getCancelRequestId() {
        return cancelRequestId;
    }

    public CancelStatus getOriginalStatus() {
        return originalStatus;
    }
}
