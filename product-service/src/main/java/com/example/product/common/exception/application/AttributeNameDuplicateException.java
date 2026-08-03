package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 속성 이름 중복 → 409 ATTRIBUTE_NAME_DUPLICATE (uk_attribute_name 위반 번역). */
public class AttributeNameDuplicateException extends BusinessException {
    public AttributeNameDuplicateException() {
        super(ErrorCode.ATTRIBUTE_NAME_DUPLICATE);
    }
}
