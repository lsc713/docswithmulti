package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class ActiveRedriveExistsException extends BusinessException {
    public ActiveRedriveExistsException(long sourceOutboxId) {
        super(ErrorCode.ACTIVE_REDRIVE_EXISTS);
    }
}
