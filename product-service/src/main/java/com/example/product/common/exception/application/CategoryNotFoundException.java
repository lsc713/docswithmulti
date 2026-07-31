package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** parentId가 가리키는 카테고리 부재 → 404 CATEGORY_NOT_FOUND. */
public class CategoryNotFoundException extends BusinessException {
    public CategoryNotFoundException(Long id) {
        super(ErrorCode.CATEGORY_NOT_FOUND, "카테고리를 찾을 수 없습니다. id=" + id);
    }
}
