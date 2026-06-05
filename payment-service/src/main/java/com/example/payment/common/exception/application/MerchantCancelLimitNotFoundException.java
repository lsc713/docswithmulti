package com.example.payment.common.exception.application;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * 가맹점 취소한도 미설정
 *
 * error-catalog.md: MERCHANT_CANCEL_LIMIT_NOT_FOUND (422)
 * domain-rules.md 3-4: 한도 필수 규칙
 * merchant_cancel_usage 레코드가 없는 가맹점의 취소 요청은 거부한다.
 */
public class MerchantCancelLimitNotFoundException extends BusinessException {

    private final Long merchantId;

    public MerchantCancelLimitNotFoundException(Long merchantId) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_NOT_FOUND, "가맹점 취소한도가 설정되지 않았습니다.");
        this.merchantId = merchantId;
    }

    public Long getMerchantId() {
        return merchantId;
    }
}
