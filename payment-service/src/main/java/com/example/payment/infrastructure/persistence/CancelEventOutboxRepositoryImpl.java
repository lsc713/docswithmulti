package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public class CancelEventOutboxRepositoryImpl implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;

    public CancelEventOutboxRepositoryImpl(CancelEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertPending(long cancelRequestId, String payload) {
        jpaRepository.insertPendingIdempotent(cancelRequestId, payload);
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        return jpaRepository
            .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, limit))
            .stream()
            .map(e -> new PendingOutbox(e.getId(), e.getCancelRequestId(), e.getPayload()))
            .toList();
    }

    @Override
    public void markPublished(long outboxId) {
        jpaRepository.findById(outboxId).ifPresent(e -> {
            e.markPublished();
            jpaRepository.save(e);
        });
    }
}
