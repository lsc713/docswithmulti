package com.example.payment.application.interfaces;

import java.time.LocalDateTime;
import java.util.List;

/**
 * stock_release_retry 재시도 계약 (CompensationRetryRepository 미러, D-P2-6).
 *
 * 예약 성공 후 payment 생성 TX가 실패했으나 best-effort release도 실패한 경우
 * 재고 예약 고아를 회수하기 위해 적재한다. release는 paymentKey+sku 멱등이라 재시도 안전.
 */
public interface StockReleaseRetryRepository {

    /** PENDING 1행 INSERT(next_retry_at=now). payment_key UK 충돌 시 무시(멱등). */
    void enqueue(String paymentKey, String itemsJson);

    /** next_retry_at <= now 이고 status = PENDING 인 레코드 조회 */
    List<PendingRelease> findDueForRetry(LocalDateTime now);

    void markDone(long id);

    /** 재시도 가능 실패 — status=PENDING 유지, nextRetryAt 갱신 */
    void markRetryLater(long id, int newAttemptCount, LocalDateTime nextRetryAt, String lastError);

    /** 최대 시도 초과 — status=FAILED 고정 */
    void exhaust(long id, int finalAttemptCount, String lastError);

    record PendingRelease(
        long id,
        String paymentKey,
        String itemsJson,
        int attemptCount
    ) {}
}
