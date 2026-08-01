package com.example.order.application.interfaces;

import java.time.Instant;
import java.util.List;

/**
 * 취소 복원 durable DLQ 포트 (Phase 1 product CancelRestoreDlqRepository 미러). cancel_restore_dlq 테이블 접근 계약.
 *
 * <p>durable 진실 — 재발행 실패/재시도 소진/NonRetryable 은 여기에 적재되고(멱등),
 * 재구동 스케줄러가 PENDING 을 재구동해 RESOLVED/DEAD 로 수렴시킨다. leg='ORDER'.
 */
public interface CancelRestoreDlqRepository {

    /**
     * cancel_request_id UK 로 멱등 적재. 신규는 PENDING INSERT, 기존은 retry_count/last_error/updated_at 만
     * UPDATE 하고 <b>status 는 건드리지 않는다</b>(RESOLVED/DEAD 행 부활 방지).
     */
    void upsertPending(String cancelRequestId, String leg, String payload, int retryCount, String lastError);

    /** status='PENDING' AND updated_at &lt;= updatedBefore 인 행을 updated_at ASC 로 최대 limit 건 반환. */
    List<Redrivable> findRedrivable(Instant updatedBefore, int limit);

    /** 재구동 성공 → status='RESOLVED'. */
    void markResolved(String cancelRequestId);

    /** 재구동 실패(임계 미만) → attempt_count+1, last_error, updated_at=now. */
    void bumpAttempt(String cancelRequestId, String lastError);

    /** 재구동 영구 실패(임계 초과) → status='DEAD' + attempt_count+1, last_error. */
    void markDead(String cancelRequestId, String lastError);

    /** 재구동 후보 한 건 (스케줄러가 payload 를 파싱해 핸들러 재호출). */
    record Redrivable(String cancelRequestId, String payload, int attemptCount) {}
}
