package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 조회 대상 상품 부재 → 404 (spec §5). */
public class ProductNotFoundException extends BusinessException {
    public ProductNotFoundException(Long id) {
        super(ErrorCode.PRODUCT_NOT_FOUND, "상품을 찾을 수 없습니다. id=" + id);
    }
}
