package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.CancelRestoreDlqRepository;

import java.time.Instant;
import java.util.List;

public class CancelRestoreDlqRepositoryImpl implements CancelRestoreDlqRepository {

    private final CancelRestoreDlqJpaRepository jpa;

    public CancelRestoreDlqRepositoryImpl(CancelRestoreDlqJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void upsertPending(String cancelRequestId, String leg, String payload, int retryCount, String lastError) {
        jpa.upsertPending(cancelRequestId, leg, payload, retryCount, lastError);
    }

    @Override
    public List<Redrivable> findRedrivable(Instant updatedBefore, int limit) {
        return jpa.findRedrivable(updatedBefore, limit).stream()
            .map(r -> new Redrivable(r.getCancelRequestId(), r.getPayload(), r.getAttemptCount()))
            .toList();
    }

    @Override
    public void markResolved(String cancelRequestId) {
        jpa.markResolved(cancelRequestId);
    }

    @Override
    public void bumpAttempt(String cancelRequestId, String lastError) {
        jpa.bumpAttempt(cancelRequestId, lastError);
    }

    @Override
    public void markDead(String cancelRequestId, String lastError) {
        jpa.markDead(cancelRequestId, lastError);
    }
}
