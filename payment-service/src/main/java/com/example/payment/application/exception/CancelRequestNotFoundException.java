package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelRequestNotFoundException extends BusinessException {

    public CancelRequestNotFoundException(long cancelRequestId) {
        super(ErrorCode.PAYMENT_NOT_FOUND,
            String.format("취소 요청을 찾을 수 없습니다. cancelRequestId=%d", cancelRequestId));
    }
}
