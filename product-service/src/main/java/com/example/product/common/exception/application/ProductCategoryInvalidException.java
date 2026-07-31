package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 상품 등록 시 categoryId 가 부재하거나 leaf(level 3)가 아님 → 400 (spec §5). */
public class ProductCategoryInvalidException extends BusinessException {
    public ProductCategoryInvalidException(Long categoryId) {
        super(ErrorCode.PRODUCT_CATEGORY_INVALID,
                "상품은 소분류(leaf) 카테고리에만 등록할 수 있습니다. categoryId=" + categoryId);
    }
}
