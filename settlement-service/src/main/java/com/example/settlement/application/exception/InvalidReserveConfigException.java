package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** 유보 정책 설정 시 값 위반(음수 rate/cap, rate≥1, scale>4, holdDays<0) → 400. */
public class InvalidReserveConfigException extends BusinessException {
    public InvalidReserveConfigException(String reason) {
        super(ErrorCode.INVALID_RESERVE_CONFIG, reason);
    }
}
