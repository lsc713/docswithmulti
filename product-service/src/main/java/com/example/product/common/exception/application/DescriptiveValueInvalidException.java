package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 서술값이 그 상품 서술 속성(is_variant=false) 소속이 아님(또는 미존재 값/속성) → 400 DESCRIPTIVE_001. */
public class DescriptiveValueInvalidException extends BusinessException {
    public DescriptiveValueInvalidException() {
        super(ErrorCode.DESCRIPTIVE_VALUE_INVALID);
    }
}
