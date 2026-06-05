package com.example.payment.domain.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelNotAuthorizedException extends BusinessException {
    public CancelNotAuthorizedException() {
        super(ErrorCode.FORBIDDEN_PAYMENT);
    }
}
