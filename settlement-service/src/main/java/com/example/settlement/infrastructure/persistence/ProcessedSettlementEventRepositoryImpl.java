package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.ProcessedSettlementEventRepository;

import java.time.Instant;

public class ProcessedSettlementEventRepositoryImpl implements ProcessedSettlementEventRepository {

    private final ProcessedSettlementEventJpaRepository jpa;

    public ProcessedSettlementEventRepositoryImpl(ProcessedSettlementEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return jpa.existsById(eventId);
    }

    @Override
    public void save(String eventId) {
        jpa.save(new ProcessedSettlementEventJpaEntity(eventId, Instant.now()));
    }
}
