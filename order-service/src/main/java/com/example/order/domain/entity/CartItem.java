package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class CartItem {

    private final long id;
    private final long userId;
    private final long skuId;
    private final long productId;
    private final String itemName;
    private final String optionSummary;
    private final long unitPrice;
    private int quantity;

    private CartItem(long id, long userId, long skuId, long productId, String itemName,
                     String optionSummary, long unitPrice, int quantity) {
        this.id = id; this.userId = userId; this.skuId = skuId; this.productId = productId;
        this.itemName = itemName; this.optionSummary = optionSummary;
        this.unitPrice = unitPrice; this.quantity = quantity;
    }

    public static CartItem create(long userId, long skuId, long productId, String itemName,
                                  String optionSummary, long unitPrice, int quantity) {
        return new CartItem(0, userId, skuId, productId, itemName, optionSummary, unitPrice, quantity);
    }

    public static CartItem of(long id, long userId, long skuId, long productId, String itemName,
                              String optionSummary, long unitPrice, int quantity) {
        return new CartItem(id, userId, skuId, productId, itemName, optionSummary, unitPrice, quantity);
    }

    public void changeQuantity(int quantity) { this.quantity = quantity; }
}
