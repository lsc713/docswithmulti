package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** GET /v1/settlements/{id}/reserve 조회 시 유보금 미존재 → 404. */
public class ReserveNotFoundException extends BusinessException {
    public ReserveNotFoundException(long settlementId) {
        super(ErrorCode.RESERVE_NOT_FOUND, "유보금을 찾을 수 없습니다. settlementId=" + settlementId);
    }
}
