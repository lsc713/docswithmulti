package com.example.settlement.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.List;

/**
 * payment.completed 이벤트 payload (payment-service PaymentCreateTxWriter.buildCompletedPayload 계약).
 * gross = totalAmount (= Σ items[].itemAmount, ×quantity 아님). completedAt 은 UTC(...Z).
 */
public record PaymentCompletedPayload(
    String paymentKey,
    long merchantId,
    BigDecimal totalAmount,
    List<Item> items,
    String completedAt
) {
    public record Item(
        long paymentItemId,
        BigDecimal itemAmount
    ) {}
}
