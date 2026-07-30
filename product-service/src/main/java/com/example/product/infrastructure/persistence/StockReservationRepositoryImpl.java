package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.domain.entity.StockReservation;

import java.util.Optional;

public class StockReservationRepositoryImpl implements StockReservationRepository {

    private final StockReservationJpaRepository jpa;

    public StockReservationRepositoryImpl(StockReservationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public StockReservation save(StockReservation reservation) {
        return jpa.save(StockReservationJpaEntity.from(reservation)).toDomain();
    }

    @Override
    public Optional<StockReservation> findByPaymentKeyAndSkuId(String paymentKey, long skuId) {
        return jpa.findByPaymentKeyAndSkuId(paymentKey, skuId).map(StockReservationJpaEntity::toDomain);
    }

    @Override
    public int upsertReserved(String paymentKey, long skuId, int qty) {
        return jpa.upsertReserved(paymentKey, skuId, qty);
    }

    @Override
    public int releaseIfReserved(String paymentKey, long skuId) {
        return jpa.releaseIfReserved(paymentKey, skuId);
    }
}
