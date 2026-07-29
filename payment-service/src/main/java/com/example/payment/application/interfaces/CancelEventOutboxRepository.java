package com.example.payment.application.interfaces;

import java.util.List;

public interface CancelEventOutboxRepository {
    void insertPending(long cancelRequestId, String payload);
    List<PendingOutbox> findPendingBatch(int limit);

    /** 주어진 outbox id들을 한 번의 UPDATE 로 PUBLISHED 처리 (커넥션 1회). 빈 리스트는 no-op. */
    void markPublished(List<Long> outboxIds);

    /** retry_count+1, last_error 갱신. status는 PENDING 유지(재시도 대상). */
    void bumpRetry(long id, String lastError);

    /** status='DEAD' 처리 + last_error 갱신. 이후 findPendingBatch에서 제외된다. */
    void markDead(long id, String lastError);

    /** published_at 기준 retentionDays 초과한 PUBLISHED 행 삭제. 삭제된 행 수 반환. */
    int purgePublished(int retentionDays);

    record PendingOutbox(long id, long cancelRequestId, String payload, int retryCount) {}
}
