package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * 동일한 Idempotency-Key가 다른 요청 내용(request_hash)으로 재사용됨
 *
 * error-catalog.md: IDEMPOTENCY_KEY_CONFLICT (409)
 * 같은 키로 이전과 다른 paymentKey/cancelPaymentItemIds 조합을 보내면 거부한다.
 */
public class IdempotencyKeyConflictException extends BusinessException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "이미 다른 요청에 사용된 Idempotency-Key입니다.");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
