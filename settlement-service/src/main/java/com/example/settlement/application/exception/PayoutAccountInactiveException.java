package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** 지급 승인 시 활성 지급 계좌 없음 → 400. */
public class PayoutAccountInactiveException extends BusinessException {
    public PayoutAccountInactiveException(long merchantId) {
        super(ErrorCode.PAYOUT_ACCOUNT_INACTIVE, "활성 지급 계좌가 없습니다: merchant=" + merchantId);
    }
}
