package com.example.product.domain.entity;

import lombok.Getter;

import java.time.Instant;

/** 순수 POJO (D-P1-2). */
@Getter
public class StockReservation {
    private final Long id;
    private final String paymentKey;
    private final Long skuId;
    private final int qty;
    private final long unitPrice;
    private final ReservationStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private StockReservation(Long id, String paymentKey, Long skuId, int qty, long unitPrice,
                             ReservationStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.paymentKey = paymentKey;
        this.skuId = skuId;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static StockReservation reserve(String paymentKey, Long skuId, int qty) {
        Instant now = Instant.now();
        return new StockReservation(null, paymentKey, skuId, qty, 0, ReservationStatus.RESERVED, now, now);
    }

    public static StockReservation reconstruct(Long id, String paymentKey, Long skuId, int qty,
                                               ReservationStatus status, Instant createdAt, Instant updatedAt) {
        return reconstruct(id, paymentKey, skuId, qty, 0, status, createdAt, updatedAt);
    }

    public static StockReservation reconstruct(Long id, String paymentKey, Long skuId, int qty, long unitPrice,
                                                ReservationStatus status, Instant createdAt, Instant updatedAt) {
        return new StockReservation(id, paymentKey, skuId, qty, unitPrice, status, createdAt, updatedAt);
    }
}
