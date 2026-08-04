package com.example.payment.application.interfaces;

import java.util.List;

/**
 * payment.completed 아웃박스 포트 (CancelEventOutboxRepository 동형, MINUS purgePublished — 폴 전용).
 * 전 메서드가 메인 datasource(JPA) 위에서 실행된다 → OutboxDataSourceConfig 무관(diff 0).
 */
public interface PaymentEventOutboxRepository {
    void insertPending(String paymentKey, String payload);
    List<PendingOutbox> findPendingBatch(int limit);

    /** 주어진 outbox id들을 한 번의 UPDATE 로 PUBLISHED 처리. 빈 리스트는 no-op. */
    void markPublished(List<Long> outboxIds);

    /** retry_count+1, last_error 갱신. status는 PENDING 유지(재시도 대상). */
    void bumpRetry(long id, String lastError);

    /** status='DEAD' 처리 + last_error 갱신. 이후 findPendingBatch에서 제외된다. */
    void markDead(long id, String lastError);

    record PendingOutbox(long id, String paymentKey, String payload, int retryCount) {}
}
