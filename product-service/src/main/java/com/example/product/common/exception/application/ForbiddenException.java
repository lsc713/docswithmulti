package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 관리자 전용 엔드포인트에 비-ADMIN 접근 → 403. */
public class ForbiddenException extends BusinessException {
    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }
}
