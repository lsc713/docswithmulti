package com.example.order.application.service;

import com.example.order.application.interfaces.CartRepository;
import com.example.order.application.usecase.CartUseCase;
import com.example.order.domain.entity.CartItem;
import com.example.order.domain.exception.CartItemNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService implements CartUseCase {

    private final CartRepository cartRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CartItem> getCart(long userId) {
        return cartRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public CartItem addItem(long userId, AddCommand c) {
        return cartRepository.findByUserIdAndSkuId(userId, c.skuId())
            .map(existing -> {
                existing.changeQuantity(existing.getQuantity() + c.quantity());
                return cartRepository.save(existing);
            })
            .orElseGet(() -> cartRepository.save(CartItem.create(
                userId, c.skuId(), c.productId(), c.itemName(), c.optionSummary(), c.unitPrice(), c.quantity())));
    }

    @Override
    @Transactional
    public CartItem updateQuantity(long userId, long skuId, int quantity) {
        CartItem item = cartRepository.findByUserIdAndSkuId(userId, skuId)
            .orElseThrow(CartItemNotFoundException::new);
        item.changeQuantity(quantity);
        return cartRepository.save(item);
    }

    @Override
    @Transactional
    public void removeItem(long userId, long skuId) {
        cartRepository.deleteByUserIdAndSkuId(userId, skuId);
    }

    @Override
    @Transactional
    public void clear(long userId) {
        cartRepository.deleteByUserId(userId);
    }
}
