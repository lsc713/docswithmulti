package com.example.order.application.interfaces;

import com.example.order.domain.entity.CartItem;
import java.util.List;
import java.util.Optional;

public interface CartRepository {
    List<CartItem> findByUserId(long userId);
    Optional<CartItem> findByUserIdAndSkuId(long userId, long skuId);
    CartItem save(CartItem item);
    void deleteByUserIdAndSkuId(long userId, long skuId);
    void deleteByUserId(long userId);
}
