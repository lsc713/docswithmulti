package com.example.order.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.List;

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
        BigDecimal itemAmount
    ) {}
}
