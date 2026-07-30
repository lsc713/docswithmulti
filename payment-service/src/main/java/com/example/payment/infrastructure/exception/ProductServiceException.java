package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/** product-service 장애(5xx/타임아웃/CircuitBreaker OPEN) → 결제 거부(fail-closed, D-P2-2). */
public class ProductServiceException extends BusinessException {

    public ProductServiceException(String message) {
        super(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE, message);
    }

    public ProductServiceException(String message, Throwable cause) {
        super(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE, message, cause);
    }
}
