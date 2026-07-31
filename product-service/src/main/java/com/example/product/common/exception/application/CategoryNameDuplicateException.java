package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 같은 부모 아래(대분류는 parent_key=0 그룹) 이름 중복 → 409 CATEGORY_NAME_DUPLICATE (spec §4, UK 위반 변환). */
public class CategoryNameDuplicateException extends BusinessException {
    public CategoryNameDuplicateException() {
        super(ErrorCode.CATEGORY_NAME_DUPLICATE);
    }
}
