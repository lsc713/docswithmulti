package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** GET /v1/settlements/reserve-config/{merchantId} 조회 시 유보 정책 미설정 → 404. */
public class ReserveConfigNotFoundException extends BusinessException {
    public ReserveConfigNotFoundException(long merchantId) {
        super(ErrorCode.RESERVE_CONFIG_NOT_FOUND, "유보 정책을 찾을 수 없습니다. merchantId=" + merchantId);
    }
}
