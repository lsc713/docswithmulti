package com.example.merchantlimit.common.exception.domain;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

public class MerchantNotFoundException extends BusinessException {
    public MerchantNotFoundException(long merchantId) {
        super(ErrorCode.MERCHANT_NOT_FOUND,
            "가맹점 정보를 찾을 수 없습니다. merchantId=" + merchantId);
    }
}
