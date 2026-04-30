package com.example.payment.application.interfaces;

import java.util.List;

public interface FailedKafkaEventRepository {

    /** AFTER_COMMIT 리스너 실패 시 신규 기록 (UK 중복 방어). */
    void saveIfAbsent(long cancelRequestId, String topic, String payload);

    boolean existsByCancelRequestId(long cancelRequestId);

    /** 스케줄러용: PENDING 건 오래된 순, 최대 limit개. */
    List<PendingFailedEvent> findPendingBatch(int limit);

    void markPublished(long cancelRequestId);
    void incrementRetry(long cancelRequestId, String error);
    void markExhausted(long cancelRequestId, String error);

    record PendingFailedEvent(long cancelRequestId, String topic, String payload, int retryCount) {}
}
