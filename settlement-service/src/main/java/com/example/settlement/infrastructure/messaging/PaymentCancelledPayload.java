package com.example.settlement.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.List;

/**
 * payment.cancelled 이벤트 payload (payment-service CancelTxWriter.buildPayload 계약).
 *
 * <p>product PaymentCancelledPayload 복제. settlement는 skuId/quantity를 쓰지 않지만
 * 하위호환을 위해 필드는 유지(Jackson 3 FAIL_ON_UNKNOWN_PROPERTIES 기본 false라 생략해도 무방).
 * cancelRequestId 는 payload JSON에서 숫자지만 String으로 코어스(order/product 동일).
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
