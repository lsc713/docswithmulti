package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProcessedCancelEventRepositoryImpl implements ProcessedCancelEventRepository {

    private final ProcessedCancelEventJpaRepository jpa;

    @Override
    public boolean existsByCancelRequestId(String cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }

    @Override
    public void save(String cancelRequestId) {
        jpa.save(ProcessedCancelEventJpaEntity.of(cancelRequestId));
    }
}
