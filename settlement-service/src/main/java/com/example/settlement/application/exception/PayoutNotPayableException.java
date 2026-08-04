package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** 지급 승인 가드 실패(FINALIZED 아님 / net ≤ 0 / 이미 지급 존재) → 400. */
public class PayoutNotPayableException extends BusinessException {
    public PayoutNotPayableException(String reason) {
        super(ErrorCode.PAYOUT_NOT_PAYABLE, "지급 승인할 수 없는 정산입니다: " + reason);
    }
}
