package com.example.payment.application.model;

import java.util.List;

public record CancelEventPayload(
    long cancelRequestId,
    String paymentKey,
    List<Item> items
) {
    public CancelEventPayload {
        items = List.copyOf(items);
    }

    public record Item(long orderItemId, long skuId, int quantity) {}
}
