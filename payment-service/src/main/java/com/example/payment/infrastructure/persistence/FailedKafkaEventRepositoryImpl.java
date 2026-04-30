package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FailedKafkaEventRepositoryImpl implements FailedKafkaEventRepository {

    private final FailedKafkaEventJpaRepository jpa;

    @Override
    @Transactional
    public void saveIfAbsent(long cancelRequestId, String topic, String payload) {
        if (jpa.existsByCancelRequestId(cancelRequestId)) return;
        jpa.save(FailedKafkaEventJpaEntity.pending(cancelRequestId, topic, payload));
    }

    @Override
    public boolean existsByCancelRequestId(long cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }

    @Override
    public List<PendingFailedEvent> findPendingBatch(int limit) {
        return jpa.findPendingBatch(limit).stream()
            .map(e -> new PendingFailedEvent(
                e.getCancelRequestId(), e.getTopic(), e.getPayload(), e.getRetryCount()))
            .toList();
    }

    @Override
    @Transactional
    public void markPublished(long cancelRequestId) {
        jpa.markPublished(cancelRequestId, Instant.now());
    }

    @Override
    @Transactional
    public void incrementRetry(long cancelRequestId, String error) {
        jpa.incrementRetry(cancelRequestId, error, Instant.now());
    }

    @Override
    @Transactional
    public void markExhausted(long cancelRequestId, String error) {
        jpa.markExhausted(cancelRequestId, error, Instant.now());
    }
}
