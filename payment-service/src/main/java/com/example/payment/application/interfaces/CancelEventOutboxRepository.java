package com.example.payment.application.interfaces;

import java.util.List;

public interface CancelEventOutboxRepository {
    void insertPending(long cancelRequestId, String payload);
    List<PendingOutbox> findPendingBatch(int limit);
    void markPublished(long outboxId);

    record PendingOutbox(long id, long cancelRequestId, String payload) {}
}
