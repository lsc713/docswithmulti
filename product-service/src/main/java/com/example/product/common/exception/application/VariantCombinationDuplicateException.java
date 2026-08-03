package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 상품 내 두 SKU 변형 조합 중복 → 409 VARIANT_002 (앱 레벨 정렬 집합 비교). */
public class VariantCombinationDuplicateException extends BusinessException {
    public VariantCombinationDuplicateException() {
        super(ErrorCode.VARIANT_COMBINATION_DUPLICATE);
    }
}
