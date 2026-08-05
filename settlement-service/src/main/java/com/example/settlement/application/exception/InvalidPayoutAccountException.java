package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** 지급 계좌 설정 시 필수 필드 누락/공백 → 400. */
public class InvalidPayoutAccountException extends BusinessException {
    public InvalidPayoutAccountException(String field) {
        super(ErrorCode.INVALID_PAYOUT_ACCOUNT, field + "는 필수입니다.");
    }
}
