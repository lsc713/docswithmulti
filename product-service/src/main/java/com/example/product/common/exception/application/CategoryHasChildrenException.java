package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class CategoryHasChildrenException extends BusinessException {

    public CategoryHasChildrenException(long categoryId) {
        super(ErrorCode.CATEGORY_HAS_CHILDREN,
                "하위 카테고리가 존재하여 삭제할 수 없습니다. categoryId=" + categoryId);
    }
}
