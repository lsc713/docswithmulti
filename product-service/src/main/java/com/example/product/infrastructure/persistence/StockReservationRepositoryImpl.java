package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.StockReservationRepository;
import com.example.product.domain.entity.StockReservation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
    public Optional<StockReservation> findByPaymentKeyAndSkuIdForUpdate(String paymentKey, long skuId) {
        return jpa.findByPaymentKeyAndSkuIdForUpdate(paymentKey, skuId)
            .map(StockReservationJpaEntity::toDomain);
    }

    @Override
    public List<StockReservation> findAllByPaymentKeyAndSkuIdInForUpdate(String paymentKey, List<Long> skuIds) {
        return jpa.findAllByPaymentKeyAndSkuIdInForUpdate(paymentKey, skuIds).stream()
            .map(StockReservationJpaEntity::toDomain)
            .toList();
    }

    @Override
    public List<StockReservation> findStaleReserved(Instant threshold) {
        LocalDateTime ldt = LocalDateTime.ofInstant(threshold, ZoneOffset.UTC);
        return jpa.findStaleReserved(ldt).stream().map(StockReservationJpaEntity::toDomain).toList();
    }

    @Override
    public int upsertReserved(String paymentKey, long skuId, int qty, long unitPrice) {
        return jpa.upsertReserved(paymentKey, skuId, qty, unitPrice);
    }

    @Override
    public int releaseAllReserved(String paymentKey, List<Long> skuIds) {
        return jpa.releaseAllReserved(paymentKey, skuIds);
    }
}
