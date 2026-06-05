package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class InvalidTokenException extends BusinessException {
    public InvalidTokenException() {
        super(ErrorCode.INVALID_TOKEN);
    }

    public InvalidTokenException(ErrorCode errorCode) {
        super(errorCode);
    }
}
