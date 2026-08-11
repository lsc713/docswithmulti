package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class InvalidRedriveReasonException extends BusinessException {
    public InvalidRedriveReasonException() {
        super(ErrorCode.INVALID_REQUEST);
    }
}
