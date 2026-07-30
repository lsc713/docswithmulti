package com.example.product.application.interfaces;

import com.example.product.domain.entity.StockReservation;

import java.util.Optional;

public interface StockReservationRepository {
    StockReservation save(StockReservation reservation);

    Optional<StockReservation> findByPaymentKeyAndSkuId(String paymentKey, long skuId);

    /**
     * 멱등 예약 게이트 (W1, D-P1-4). 차감보다 앞세워 uk가 winner/loser를 직렬화.
     * @return 1 = 신규(차감), 0 = 이미 예약됨(재사용, 차감 없음)
     */
    int upsertReserved(String paymentKey, long skuId, int qty);

    /**
     * release 원자 조건부 전이 RESERVED→RELEASED (W2).
     * @return 1 = 전이 성공(복원 트리거), 0 = 이미 RELEASED/미존재(no-op)
     */
    int releaseIfReserved(String paymentKey, long skuId);
}
