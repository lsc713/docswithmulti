package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 소분류(level 3) 아래에 자식(level 4) 생성 시도 → 400 CATEGORY_DEPTH_EXCEEDED (spec §4). */
public class CategoryDepthExceededException extends BusinessException {
    public CategoryDepthExceededException() {
        super(ErrorCode.CATEGORY_DEPTH_EXCEEDED);
    }
}
