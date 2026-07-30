package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockReservationJpaRepository extends JpaRepository<StockReservationJpaEntity, Long> {
    Optional<StockReservationJpaEntity> findByPaymentKeyAndSkuId(String paymentKey, long skuId);
}
