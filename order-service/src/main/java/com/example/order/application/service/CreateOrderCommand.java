package com.example.order.application.service;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderCommand(
    long userId,
    List<Item> items
) {
    public record Item(
        long productId,
        String itemName,
        BigDecimal price
    ) {}
}
