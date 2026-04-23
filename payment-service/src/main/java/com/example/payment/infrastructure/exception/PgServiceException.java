package com.example.payment.infrastructure.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class PgServiceException extends BusinessException {

    public PgServiceException(String message) {
        super(ErrorCode.PG_SERVICE_UNAVAILABLE, message);
    }

    public PgServiceException(String message, Throwable cause) {
        super(ErrorCode.PG_SERVICE_UNAVAILABLE, message, cause);
    }
}
