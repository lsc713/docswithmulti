package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** GET /v1/settlements/{id}/payout 조회 시 지급 건 없음 → 404 (SettlementNotFoundException 과 동일 클래스). */
public class PayoutNotFoundException extends BusinessException {
    public PayoutNotFoundException(long settlementId) {
        super(ErrorCode.PAYOUT_NOT_FOUND, "지급 건을 찾을 수 없습니다. settlementId=" + settlementId);
    }
}
