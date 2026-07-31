package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * order-service 검증 거부(404 ORDER_ITEM_NOT_FOUND / 409 ORDER_ITEMS_MULTIPLE_ORDERS /
 * 403 ORDER_OWNERSHIP_MISMATCH) → 결제 거부. 판정된 ErrorCode를 그대로 실어
 * GlobalExceptionHandler가 동일 상태코드로 응답한다.
 */
public class OrderVerifyRejectedException extends BusinessException {

    public OrderVerifyRejectedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public OrderVerifyRejectedException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
