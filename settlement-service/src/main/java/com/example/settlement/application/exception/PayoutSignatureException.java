package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** 지급 결과 webhook 서명(shared-secret) 불일치 → 401. 상태는 무변경. */
public class PayoutSignatureException extends BusinessException {
    public PayoutSignatureException() {
        super(ErrorCode.PAYOUT_SIGNATURE_INVALID);
    }
}
