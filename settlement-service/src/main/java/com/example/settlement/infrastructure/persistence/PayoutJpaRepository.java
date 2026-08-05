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

    /**
     * 재시도 claim: FAILED + attempt_count < max 건을 원자 UPDATE 로 PROCESSING 재진입 + attempt_count++.
     * clone applyResult shape. WHERE 가 승자 1건만 반영 → 두 tick 이 동시에 봐도 정확히 1건만 claim(rowcount==1),
     * 나머지는 0-row no-op → 은행 재제출은 claim 한 tick 만(이중지급 차단). markDeadIfExhausted 와 disjoint(< vs >=).
     * @return 갱신 행 수. 1 = 이 tick 이 claim → submit. 0 = 이미 claim 됨/경합/exhausted.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE payout
           SET status = 'PROCESSING',
               attempt_count = attempt_count + 1,
               updated_at = CURRENT_TIMESTAMP(3)
         WHERE transfer_ref = :ref AND status = 'FAILED' AND attempt_count < :max
        """, nativeQuery = true)
    int claimForRetry(@Param("ref") String transferRef, @Param("max") int max);

    /**
     * 소진 terminal: FAILED + attempt_count >= max 건을 원자 UPDATE 로 DEAD 전이(cancel-restore DLQ DEAD 관용).
     * DEAD 는 기존 status VARCHAR(20) 의 새 문자열 값 — CHECK/enum/Flyway 없음(ddl-auto=validate 는 컬럼 shape 만 검증).
     * WHERE 가 승자 1건만 반영 → alert-once: rowcount==1 인 tick 만 알림. 이후 tick 은 DEAD 라 FAILED select 에 재선별 안 됨.
     * @return 갱신 행 수. 1 = 이 tick 이 DEAD 전이 → alert 1회. 0 = 이미 DEAD/경합 → no alert.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE payout
           SET status = 'DEAD',
               last_error = :err,
               updated_at = CURRENT_TIMESTAMP(3)
         WHERE transfer_ref = :ref AND status = 'FAILED' AND attempt_count >= :max
        """, nativeQuery = true)
    int markDeadIfExhausted(@Param("ref") String transferRef,
                            @Param("max") int max,
                            @Param("err") String err);
}
