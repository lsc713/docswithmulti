package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StockReservationJpaRepository extends JpaRepository<StockReservationJpaEntity, Long> {
    Optional<StockReservationJpaEntity> findByPaymentKeyAndSkuId(String paymentKey, long skuId);

    @Query(value = """
        SELECT * FROM stock_reservation
        WHERE payment_key = :paymentKey AND sku_id = :skuId
        FOR UPDATE
        """, nativeQuery = true)
    Optional<StockReservationJpaEntity> findByPaymentKeyAndSkuIdForUpdate(
        String paymentKey, long skuId);

    @Query(value = """
        SELECT * FROM stock_reservation
         WHERE payment_key = :paymentKey AND sku_id IN (:skuIds)
         ORDER BY sku_id
         FOR UPDATE
        """, nativeQuery = true)
    List<StockReservationJpaEntity> findAllByPaymentKeyAndSkuIdInForUpdate(
        @Param("paymentKey") String paymentKey, @Param("skuIds") List<Long> skuIds);

    /**
     * orphan 스캔 (RST-03): status='RESERVED' AND created_at &lt; threshold, idx_reservation_status_created 활용.
     * created_at 오름차순 LIMIT 500 배치 — 오래된 것부터 정리, 한 주기 처리량 상한.
     */
    @Query(value = """
        SELECT * FROM stock_reservation
         WHERE status = 'RESERVED' AND created_at < :threshold
         ORDER BY created_at ASC
         LIMIT 500
        """, nativeQuery = true)
    List<StockReservationJpaEntity> findStaleReserved(@Param("threshold") LocalDateTime threshold);

    /**
     * 멱등 예약 게이트 (W1, D-P1-4, risk ensureRow 미러).
     * 예약 INSERT를 차감보다 앞세워 uk_reservation_paymentkey_sku 가 winner/loser를 직렬화한다.
     * ON DUPLICATE KEY UPDATE 는 no-op(payment_key=payment_key).
     * <p>affected 판별은 JDBC {@code useAffectedRows=true} 를 전제로 한다(기본 false면 no-op도 1 반환).
     * @return 1 = 신규 예약(이 요청이 소유 → 차감), 0 = 이미 예약됨(loser → 재사용, 차감 없음)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO stock_reservation
            (payment_key, sku_id, qty, unit_price, status, created_at, updated_at)
        VALUES (:paymentKey, :skuId, :qty, :unitPrice, 'RESERVED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE payment_key = payment_key
        """, nativeQuery = true)
    int upsertReserved(String paymentKey, long skuId, int qty, long unitPrice);

    /**
     * release 원자 조건부 상태전이 (W2, D-P1-4).
     * RESERVED → RELEASED 전이가 재고 복원의 유일 트리거. DB가 행락으로 전이를 직렬화하므로
     * 동시 이중 release라도 정확히 1건만 affected=1 → 복원 1회(over-release 불가).
     * @return 1 = 전이 성공(복원 트리거), 0 = 이미 RELEASED/미존재(no-op)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE stock_reservation
           SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP(6)
         WHERE payment_key = :paymentKey AND sku_id IN (:skuIds) AND status = 'RESERVED'
        """, nativeQuery = true)
    int releaseAllReserved(@Param("paymentKey") String paymentKey, @Param("skuIds") List<Long> skuIds);
}
