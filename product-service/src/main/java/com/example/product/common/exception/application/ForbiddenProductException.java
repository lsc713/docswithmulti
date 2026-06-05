package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class ForbiddenProductException extends BusinessException {

    public ForbiddenProductException() {
        super(ErrorCode.FORBIDDEN_PRODUCT);
    }
}
