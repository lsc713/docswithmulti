package com.example.product.common.exception.domain;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 재고 부족 예외
 *
 * 차감 요청량이 가용 재고를 초과할 때 발생한다.
 */
@Getter
public class InsufficientStockException extends BusinessException {

    private final long skuId;
    private final int requested;
    private final int available;

    public InsufficientStockException(long skuId, int requested, int available) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                String.format("SKU %d: 요청 수량 %d, 가용 재고 %d", skuId, requested, available));
        this.skuId = skuId;
        this.requested = requested;
        this.available = available;
    }
}
