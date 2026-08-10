package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelOutboxNotFoundException extends BusinessException {
    public CancelOutboxNotFoundException(long outboxId) {
        super(ErrorCode.CANCEL_OUTBOX_NOT_FOUND,
            "취소 아웃박스를 찾을 수 없습니다. outboxId=" + outboxId);
    }
}
