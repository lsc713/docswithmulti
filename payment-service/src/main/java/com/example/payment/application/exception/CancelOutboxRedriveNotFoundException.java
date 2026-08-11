package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelOutboxRedriveNotFoundException extends BusinessException {
    public CancelOutboxRedriveNotFoundException(long redriveId) {
        super(ErrorCode.CANCEL_OUTBOX_REDRIVE_NOT_FOUND);
    }
}
