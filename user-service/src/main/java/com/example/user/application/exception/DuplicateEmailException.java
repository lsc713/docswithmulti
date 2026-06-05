package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException(String email) {
        super(ErrorCode.DUPLICATE_EMAIL, "이미 등록된 이메일입니다. email=" + email);
    }
}
