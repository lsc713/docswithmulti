package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelOutboxForbiddenException extends BusinessException {
    public CancelOutboxForbiddenException() {
        super(ErrorCode.CANCEL_OUTBOX_REDRIVE_FORBIDDEN);
    }
}
