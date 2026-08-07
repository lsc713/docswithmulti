package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface ReserveJpaRepository extends JpaRepository<ReserveJpaEntity, Long> {

    /**
     * 가맹점 누적 held Σamount (cap 계산). RELEASING 은 Phase 2까지 행 없음(forward-compat).
     * COALESCE(SUM,0) — 0행이면 0 반환(NPE 방지, SettlementLineJpaRepository.sumByType 패턴).
     */
    @Query(value = """
        SELECT COALESCE(SUM(amount), 0)
          FROM reserve
         WHERE merchant_id = :merchantId AND status IN ('HELD','RELEASING')
        """, nativeQuery = true)
    BigDecimal currentHeld(@Param("merchantId") long merchantId);

    /**
     * 승인 시 HELD 유보 행 INSERT. <b>자체 @Transactional 필수</b>(PayoutJpaRepository.applyResult 미러) —
     * non-@Transactional approve 에서 payout INSERT 성공 후 호출되는 독립 durable write.
     * uk_reserve_settlement 가 재승인·경합 시 중복 유보를 DB 레벨에서 차단.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO reserve
            (settlement_id, merchant_id, amount, status, hold_until, transfer_ref,
             attempt_count, last_error, held_at, released_at, created_at, updated_at)
        VALUES (:settlementId, :merchantId, :amount, 'HELD', :holdUntil, :transferRef,
                1, NULL, CURRENT_TIMESTAMP(3), NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        """, nativeQuery = true)
    int insertHeld(@Param("settlementId") long settlementId,
                   @Param("merchantId") long merchantId,
                   @Param("amount") BigDecimal amount,
                   @Param("holdUntil") LocalDate holdUntil,
                   @Param("transferRef") String transferRef);

    Optional<ReserveJpaEntity> findBySettlementId(long settlementId);
}
