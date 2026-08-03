package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface SettlementJpaRepository extends JpaRepository<SettlementJpaEntity, Long> {

    /** (merchant_id, period_start) 헤더가 없으면 OPEN으로 생성(멱등). 충돌 시 no-op. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO settlement
            (merchant_id, period_start, period_end, status, created_at, updated_at)
        VALUES (:merchantId, :periodStart, :periodEnd, 'OPEN', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE merchant_id = merchant_id
        """, nativeQuery = true)
    int ensureRow(@Param("merchantId") long merchantId,
                  @Param("periodStart") LocalDate periodStart,
                  @Param("periodEnd") LocalDate periodEnd);

    /**
     * cancel_amount 원자 증분. 단일 문장이라 read-modify-write 갭 없음(lost update 없음).
     * @return 1 = 성공(대상 행 존재)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE settlement
           SET cancel_amount = cancel_amount + :amount, updated_at = CURRENT_TIMESTAMP(3)
         WHERE merchant_id = :merchantId AND period_start = :periodStart
        """, nativeQuery = true)
    int addCancelAmount(@Param("merchantId") long merchantId,
                        @Param("periodStart") LocalDate periodStart,
                        @Param("amount") BigDecimal amount);

    @Query(value = "SELECT id FROM settlement WHERE merchant_id = :merchantId AND period_start = :periodStart",
        nativeQuery = true)
    Long findIdByMerchantIdAndPeriodStart(@Param("merchantId") long merchantId,
                                          @Param("periodStart") LocalDate periodStart);

    List<SettlementJpaEntity> findByMerchantIdOrderByPeriodStartDesc(long merchantId);

    List<SettlementJpaEntity> findByMerchantIdAndStatusOrderByPeriodStartDesc(long merchantId, String status);
}
