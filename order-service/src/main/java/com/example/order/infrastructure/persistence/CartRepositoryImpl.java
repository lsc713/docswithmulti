package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.CartRepository;
import com.example.order.domain.entity.CartItem;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class CartRepositoryImpl implements CartRepository {

    private final CartItemJpaRepository jpa;

    @Override
    public List<CartItem> findByUserId(long userId) {
        return jpa.findByUserIdOrderByIdAsc(userId).stream().map(CartItemJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<CartItem> findByUserIdAndSkuId(long userId, long skuId) {
        return jpa.findByUserIdAndSkuId(userId, skuId).map(CartItemJpaEntity::toDomain);
    }

    @Override
    public CartItem save(CartItem item) {
        CartItemJpaEntity e;
        if (item.getId() == 0) {
            e = CartItemJpaEntity.forInsert(item);       // 신규 INSERT
        } else {
            e = jpa.findById(item.getId()).orElseThrow(); // 기존 로드 후 수량만 갱신(created_at 보존)
            e.applyQuantity(item.getQuantity());
        }
        return jpa.save(e).toDomain();
    }

    @Override
    public void deleteByUserIdAndSkuId(long userId, long skuId) { jpa.deleteByUserIdAndSkuId(userId, skuId); }

    @Override
    public void deleteByUserId(long userId) { jpa.deleteByUserId(userId); }
}
