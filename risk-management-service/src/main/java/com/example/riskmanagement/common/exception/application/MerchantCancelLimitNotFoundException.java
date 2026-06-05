package com.example.riskmanagement.common.exception.application;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

public class MerchantCancelLimitNotFoundException extends BusinessException {
    public MerchantCancelLimitNotFoundException(long merchantId) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_NOT_FOUND,
            "가맹점 취소한도가 설정되지 않았습니다. merchantId=" + merchantId);
    }
}
