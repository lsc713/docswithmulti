package com.example.merchantlimit.common.exception.domain;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

public class MerchantSuspendedException extends BusinessException {
    public MerchantSuspendedException(long merchantId) {
        super(ErrorCode.MERCHANT_SUSPENDED,
            "정지된 가맹점의 한도를 변경할 수 없습니다. merchantId=" + merchantId);
    }
}
