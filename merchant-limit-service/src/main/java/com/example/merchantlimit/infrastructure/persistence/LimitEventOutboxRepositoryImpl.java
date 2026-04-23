package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public class LimitEventOutboxRepositoryImpl implements LimitEventOutboxRepository {

    private final LimitEventOutboxJpaRepository jpaRepository;

    public LimitEventOutboxRepositoryImpl(LimitEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertPending(long merchantId, String payload) {
        jpaRepository.save(LimitEventOutboxJpaEntity.pending(merchantId, payload));
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        return jpaRepository
            .findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, limit))
            .stream()
            .map(e -> new PendingOutbox(e.getId(), e.getMerchantId(), e.getPayload()))
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
