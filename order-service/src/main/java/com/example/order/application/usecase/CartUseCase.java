package com.example.order.application.usecase;

import com.example.order.domain.entity.CartItem;

import java.util.List;

public interface CartUseCase {

    record AddCommand(long skuId, long productId, String itemName, String optionSummary,
                      long unitPrice, int quantity) {}

    List<CartItem> getCart(long userId);
    CartItem addItem(long userId, AddCommand cmd);
    CartItem updateQuantity(long userId, long skuId, int quantity);
    void removeItem(long userId, long skuId);
    void clear(long userId);
}
