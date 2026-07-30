package com.example.user.common.exception.application;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class UserNotFoundException extends BusinessException {
    public UserNotFoundException(long userId) {
        super(ErrorCode.USER_NOT_FOUND, "유저를 찾을 수 없습니다. id=" + userId);
    }
}
