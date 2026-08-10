package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class InternalAuthenticationRequiredException extends BusinessException {
    public InternalAuthenticationRequiredException() {
        super(ErrorCode.INTERNAL_AUTHENTICATION_REQUIRED);
    }
}
