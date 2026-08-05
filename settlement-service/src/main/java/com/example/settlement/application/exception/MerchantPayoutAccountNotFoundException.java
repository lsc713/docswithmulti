package com.example.settlement.application.exception;

import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.common.exception.ErrorCode;

/** GET /v1/settlements/payout-account/{merchantId} 조회 시 활성 계좌 없음 → 404. */
public class MerchantPayoutAccountNotFoundException extends BusinessException {
    public MerchantPayoutAccountNotFoundException(long merchantId) {
        super(ErrorCode.PAYOUT_ACCOUNT_NOT_FOUND, "지급 계좌를 찾을 수 없습니다. merchantId=" + merchantId);
    }
}
