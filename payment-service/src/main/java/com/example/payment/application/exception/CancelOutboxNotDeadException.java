package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelOutboxNotDeadException extends BusinessException {
    public CancelOutboxNotDeadException(long sourceOutboxId) {
        super(ErrorCode.CANCEL_OUTBOX_NOT_DEAD);
    }
}
