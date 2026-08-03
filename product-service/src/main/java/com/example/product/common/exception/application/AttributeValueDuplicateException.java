package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 같은 속성 내 값 중복 → 409 ATTRIBUTE_VALUE_DUPLICATE (uk_attribute_value 위반 번역). */
public class AttributeValueDuplicateException extends BusinessException {
    public AttributeValueDuplicateException() {
        super(ErrorCode.ATTRIBUTE_VALUE_DUPLICATE);
    }
}
