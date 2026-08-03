package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** SKU 변형 조합이 선언 변형 속성을 정확히 하나씩 커버하지 않음 → 400 VARIANT_001. */
public class VariantIncompleteException extends BusinessException {
    public VariantIncompleteException() {
        super(ErrorCode.VARIANT_INCOMPLETE);
    }
}
