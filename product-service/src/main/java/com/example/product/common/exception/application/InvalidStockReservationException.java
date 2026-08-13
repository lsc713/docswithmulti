package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

public class InvalidStockReservationException extends BusinessException {
    public InvalidStockReservationException(long productId, long skuId) {
        this("SKU가 요청 상품에 속하지 않습니다. productId=" + productId + ", skuId=" + skuId);
    }

    public InvalidStockReservationException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
