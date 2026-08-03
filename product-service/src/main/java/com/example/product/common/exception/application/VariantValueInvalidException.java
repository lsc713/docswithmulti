package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 변형값이 그 상품 변형 속성 소속이 아님(또는 미존재 값/속성) → 400 VARIANT_003. */
public class VariantValueInvalidException extends BusinessException {
    public VariantValueInvalidException() {
        super(ErrorCode.VARIANT_VALUE_INVALID);
    }
}
