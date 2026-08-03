package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 없는 속성에 값 추가 → 404 ATTRIBUTE_NOT_FOUND. */
public class AttributeNotFoundException extends BusinessException {
    public AttributeNotFoundException(Long attributeId) {
        super(ErrorCode.ATTRIBUTE_NOT_FOUND, "속성을 찾을 수 없습니다: " + attributeId);
    }
}
