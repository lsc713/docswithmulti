package com.example.payment.application.service;

import java.math.BigDecimal;
import java.util.List;

public record CreatePaymentCommand(
    long merchantId,
    long userId,
    String pgType,
    int cancelPeriodDays,
    List<Item> items
) {
    public record Item(
        long orderItemId,
        long productId,
        long skuId,
        int quantity,
        String itemName,
        BigDecimal itemAmount
    ) {}
}
