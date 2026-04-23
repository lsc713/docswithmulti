package com.example.riskmanagement.infrastructure.http;

import com.example.riskmanagement.common.exception.BusinessException;
import com.example.riskmanagement.common.exception.ErrorCode;

public class MerchantNotFoundException extends BusinessException {

    public MerchantNotFoundException(long merchantId) {
        super(ErrorCode.MERCHANT_CANCEL_LIMIT_NOT_FOUND,
            "가맹점을 찾을 수 없습니다. merchantId=" + merchantId);
    }
}
