package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/** product reserve 409(재고 부족) → 결제 거부(fail-closed, D-P2-2). */
public class StockInsufficientException extends BusinessException {

    public StockInsufficientException(String message) {
        super(ErrorCode.STOCK_INSUFFICIENT, message);
    }

    public StockInsufficientException(String message, Throwable cause) {
        super(ErrorCode.STOCK_INSUFFICIENT, message, cause);
    }
}
