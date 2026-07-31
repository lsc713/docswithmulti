package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProcessedCancelEventRepository;

public class ProcessedCancelEventRepositoryImpl implements ProcessedCancelEventRepository {

    private final ProcessedCancelEventJpaRepository jpa;

    public ProcessedCancelEventRepositoryImpl(ProcessedCancelEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByCancelRequestId(String cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }

    @Override
    public void save(String cancelRequestId) {
        jpa.save(ProcessedCancelEventJpaEntity.of(cancelRequestId));
    }
}
