package com.example.user.common.exception.domain;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS);
    }
}
