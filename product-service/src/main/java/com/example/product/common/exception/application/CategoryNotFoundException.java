package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class CategoryNotFoundException extends BusinessException {

    public CategoryNotFoundException(long categoryId) {
        super(ErrorCode.CATEGORY_NOT_FOUND, "카테고리를 찾을 수 없습니다. categoryId=" + categoryId);
    }
}
