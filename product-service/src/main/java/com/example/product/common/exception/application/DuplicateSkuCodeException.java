package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class DuplicateSkuCodeException extends BusinessException {

    public DuplicateSkuCodeException(String skuCode) {
        super(ErrorCode.DUPLICATE_SKU_CODE, "이미 존재하는 SKU 코드입니다. skuCode=" + skuCode);
    }
}
