package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;
import com.example.settlement.domain.entity.Payout;

/**
 * 정산에 이미 지급 건이 존재(uk_payout_settlement) → 409. 순차 재승인(pre-check) 또는 경합 패자
 * (DataIntegrityViolationException). 승자 Payout 을 담아 GlobalExceptionHandler 가 409+기존 payout 바디로 응답.
 */
public class PayoutAlreadyExistsException extends BusinessException {
    private final Payout existing;

    public PayoutAlreadyExistsException(Payout existing) {
        super(ErrorCode.PAYOUT_ALREADY_EXISTS, "이미 지급 건이 존재합니다. settlement=" + existing.getSettlementId());
        this.existing = existing;
    }

    public Payout getExisting() {
        return existing;
    }
}
