package com.example.product.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.List;

/**
 * payment.cancelled 이벤트 payload (payment-service CancelTxWriter.buildPayload 계약).
 *
 * <p>order-service PaymentCancelledPayload 복제 + cancelledItems 에 skuId/quantity 추가(RST-01).
 * cancelRequestId 는 payload JSON 에서 숫자지만 String 으로 코어스(order 와 동일).
 * skuId 는 nullable — 하위호환(재고 없이 생성된 과거 건)은 JSON null 로 실림.
 */
public record PaymentCancelledPayload(
    String cancelRequestId,
    String paymentKey,
    long merchantId,
    List<CancelledItem> cancelledItems,
    String cancelledAt
) {
    public record CancelledItem(
        long paymentItemId,
        long orderItemId,
        BigDecimal itemAmount,
        Long skuId,
        int quantity
    ) {}
}
