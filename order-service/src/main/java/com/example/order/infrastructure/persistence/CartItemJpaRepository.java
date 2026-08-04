package com.example.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CartItemJpaRepository extends JpaRepository<CartItemJpaEntity, Long> {
    List<CartItemJpaEntity> findByUserIdOrderByIdAsc(Long userId);
    Optional<CartItemJpaEntity> findByUserIdAndSkuId(Long userId, Long skuId);
    void deleteByUserIdAndSkuId(Long userId, Long skuId);
    void deleteByUserId(Long userId);
}
