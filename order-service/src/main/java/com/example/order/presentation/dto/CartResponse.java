package com.example.order.presentation.dto;

import com.example.order.domain.entity.CartItem;

import java.util.List;

public record CartResponse(List<Item> items) {

    public record Item(long skuId, long productId, String itemName, String optionSummary,
                       long unitPrice, int quantity) {}

    public static CartResponse from(List<CartItem> items) {
        return new CartResponse(items.stream()
            .map(c -> new Item(c.getSkuId(), c.getProductId(), c.getItemName(),
                c.getOptionSummary(), c.getUnitPrice(), c.getQuantity()))
            .toList());
    }
}
