package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.Payout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutRepository {

    /** 승인 시점 PROCESSING 신규 건 INSERT(단일 write). transfer_ref 는 호출부가 결정('PO-'+settlementId). */
    Payout insertProcessing(long settlementId, long merchantId, BigDecimal amount, String transferRef);

    Optional<Payout> findBySettlementId(long settlementId);

    Optional<Payout> findByTransferRef(String transferRef);

    /** poll backstop: status='PROCESSING' AND updated_at < cutoff. */
    List<Payout> findStuckProcessing(Instant cutoff);

    /** 재시도 대상: status='FAILED' AND updated_at < cutoff(grace backoff). */
    List<Payout> findRetryableFailed(Instant cutoff);

    /**
     * status-guarded 원자 UPDATE(WHERE transfer_ref=? AND status='PROCESSING'). webhook·poll·중복 수렴 지점.
     * @return 갱신 행 수. 0 = 이미 terminal/경합 → no-op.
     */
    int applyResult(String transferRef, String result, String err);

    /**
     * FAILED→PROCESSING attempt++ 원자 claim(WHERE status='FAILED' AND attempt_count < max).
     * @return 1 = 이 tick claim(→submit), 0 = 경합/이미 claim/exhausted.
     */
    int claimForRetry(String transferRef, int max);

    /**
     * FAILED→DEAD 원자 전이(WHERE status='FAILED' AND attempt_count >= max).
     * @return 1 = 이 tick DEAD 전이(→alert 1회), 0 = 이미 DEAD/경합.
     */
    int markDeadIfExhausted(String transferRef, int max, String err);
}
