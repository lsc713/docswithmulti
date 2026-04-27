package com.example.payment.application.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface CompensationRetryRepository {

    void save(long cancelRequestId, long merchantId, BigDecimal restoreAmount);

    /** next_retry_at <= now 이고 status = PENDING 인 레코드 조회 */
    List<PendingCompensation> findDueForRetry(Instant now);

    void markDone(long id);

    /** 재시도 가능 실패 — status=PENDING 유지, nextRetryAt 갱신 */
    void markRetryLater(long id, int newAttemptCount, Instant nextRetryAt, String lastError);

    /** 최대 시도 초과 — status=FAILED 고정 */
    void exhaust(long id, int finalAttemptCount, String lastError);

    record PendingCompensation(
        long id,
        long cancelRequestId,
        long merchantId,
        BigDecimal restoreAmount,
        int attemptCount
    ) {}
}
