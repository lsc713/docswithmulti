package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** confirm 시 오브젝트 스토리지에 실존하지 않는 key → 400. */
public class InvalidImageKeyException extends BusinessException {
    public InvalidImageKeyException(String key) {
        super(ErrorCode.IMAGE_KEY_INVALID, "존재하지 않는 이미지 키입니다. key=" + key);
    }
}
