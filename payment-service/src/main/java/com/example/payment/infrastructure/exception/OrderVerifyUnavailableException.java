package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * order-service 장애(5xx/타임아웃/CircuitBreaker OPEN/비2xx 응답) → 결제 거부(fail-closed, PLINK-01).
 * ProductServiceException과 동형 — 검증된 X-User-Id 소유 판단을 payment가 대신할 수 없으므로
 * order-service에 도달 실패하면 결제를 만들지 않는다.
 */
public class OrderVerifyUnavailableException extends BusinessException {

    public OrderVerifyUnavailableException(String message) {
        super(ErrorCode.ORDER_VERIFY_UNAVAILABLE, message);
    }

    public OrderVerifyUnavailableException(String message, Throwable cause) {
        super(ErrorCode.ORDER_VERIFY_UNAVAILABLE, message, cause);
    }
}
