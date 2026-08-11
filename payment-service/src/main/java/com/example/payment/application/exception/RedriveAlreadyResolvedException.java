package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class RedriveAlreadyResolvedException extends BusinessException {
    public RedriveAlreadyResolvedException(long sourceOutboxId) {
        super(ErrorCode.REDRIVE_ALREADY_RESOLVED);
    }
}
