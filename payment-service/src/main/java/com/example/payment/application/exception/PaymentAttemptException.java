package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class PaymentAttemptException extends BusinessException {
    public PaymentAttemptException(ErrorCode errorCode) {
        super(errorCode);
    }

    public PaymentAttemptException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
