package com.example.user.domain.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class SuspendedAccountException extends BusinessException {
    public SuspendedAccountException() {
        super(ErrorCode.SUSPENDED_ACCOUNT);
    }
}
