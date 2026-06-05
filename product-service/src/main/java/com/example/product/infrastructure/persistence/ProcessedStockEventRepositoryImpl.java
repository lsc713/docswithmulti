package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProcessedStockEventRepository;
import com.example.product.domain.entity.ProcessedStockEvent;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProcessedStockEventRepositoryImpl implements ProcessedStockEventRepository {

    private final ProcessedStockEventJpaRepository jpaRepository;

    @Override
    public boolean existsByCancelRequestId(long cancelRequestId) {
        return jpaRepository.existsByCancelRequestId(cancelRequestId);
    }

    @Override
    public ProcessedStockEvent save(ProcessedStockEvent event) {
        return jpaRepository.save(ProcessedStockEventJpaEntity.from(event)).toDomain();
    }
}
