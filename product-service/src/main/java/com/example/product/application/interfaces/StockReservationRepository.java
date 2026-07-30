package com.example.product.application.interfaces;

import com.example.product.domain.entity.StockReservation;

import java.util.Optional;

public interface StockReservationRepository {
    StockReservation save(StockReservation reservation);

    Optional<StockReservation> findByPaymentKeyAndSkuId(String paymentKey, long skuId);
}
