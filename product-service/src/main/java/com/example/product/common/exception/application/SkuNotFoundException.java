package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class SkuNotFoundException extends BusinessException {

    public SkuNotFoundException(long skuId) {
        super(ErrorCode.SKU_NOT_FOUND, "SKU를 찾을 수 없습니다. skuId=" + skuId);
    }
}
