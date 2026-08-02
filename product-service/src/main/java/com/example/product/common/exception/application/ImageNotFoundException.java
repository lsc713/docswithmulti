package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** 삭제 대상 이미지 부재 → 404. */
public class ImageNotFoundException extends BusinessException {
    public ImageNotFoundException(Long id) {
        super(ErrorCode.IMAGE_NOT_FOUND, "이미지를 찾을 수 없습니다. id=" + id);
    }
}
