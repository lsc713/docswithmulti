package com.example.merchantlimit.application.interfaces;

import java.util.List;

public interface LimitEventOutboxRepository {
    void insertPending(long merchantId, String payload);
    List<PendingOutbox> findPendingBatch(int limit);
    void markPublished(long outboxId);

    record PendingOutbox(long id, long merchantId, String payload) {}
}
