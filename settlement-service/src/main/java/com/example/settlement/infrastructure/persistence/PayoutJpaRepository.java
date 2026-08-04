package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutJpaRepository extends JpaRepository<PayoutJpaEntity, Long> {

    Optional<PayoutJpaEntity> findBySettlementId(long settlementId);

    Optional<PayoutJpaEntity> findByTransferRef(String transferRef);

    /** poll backstop: status='PROCESSING' AND updated_at < cutoff. idx_payout_status 부분 커버. */
    List<PayoutJpaEntity> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    /**
     * 지급 결과를 status-guarded 단일 원자 UPDATE 로 확정(clone SettlementJpaRepository.finalizeOpen).
     * webhook·poll·중복 콜백이 모두 이 한 지점으로 수렴 — WHERE status='PROCESSING' 가 승자 1건만 반영.
     * @return 갱신 행 수. 0 = 이미 terminal/경합 → no-op(호출부가 로그, blind retry 금지).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE payout
           SET status = :result,
               paid_at = CASE WHEN :result = 'PAID' THEN CURRENT_TIMESTAMP(3) ELSE paid_at END,
               last_error = :err,
               updated_at = CURRENT_TIMESTAMP(3)
         WHERE transfer_ref = :ref AND status = 'PROCESSING'
        """, nativeQuery = true)
    int applyResult(@Param("ref") String transferRef,
                    @Param("result") String result,
                    @Param("err") String err);
}
