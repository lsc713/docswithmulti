package com.example.product.common.exception.application;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;

/** reserve 시 available_qty < qty → 409 STOCK_INSUFFICIENT (오버셀 거부, D-P1-3). */
public class StockInsufficientException extends BusinessException {
    public StockInsufficientException(long skuId) {
        super(ErrorCode.STOCK_INSUFFICIENT, "재고가 부족합니다. skuId=" + skuId);
    }
}
