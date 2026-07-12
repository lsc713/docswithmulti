package com.example.payment.application.interfaces;

import java.util.List;

public interface CancelEventOutboxRepository {
    void insertPending(long cancelRequestId, String payload);
    List<PendingOutbox> findPendingBatch(int limit);

    /** 주어진 outbox id들을 한 번의 UPDATE 로 PUBLISHED 처리 (커넥션 1회). 빈 리스트는 no-op. */
    void markPublished(List<Long> outboxIds);

    record PendingOutbox(long id, long cancelRequestId, String payload) {}
}
