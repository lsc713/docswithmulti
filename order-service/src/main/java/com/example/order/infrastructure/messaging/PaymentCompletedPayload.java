package com.example.order.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.List;

public record PaymentCompletedPayload(
    String paymentKey,
    Long orderId,
    long merchantId,
    BigDecimal totalAmount,
    List<Item> items,
    String completedAt
) {
    public record Item(long paymentItemId, BigDecimal itemAmount) {}
}
