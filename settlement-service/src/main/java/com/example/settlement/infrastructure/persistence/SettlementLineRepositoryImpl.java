package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.domain.entity.SettlementLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class SettlementLineRepositoryImpl implements SettlementLineRepository {

    private final SettlementLineJpaRepository jpa;

    public SettlementLineRepositoryImpl(SettlementLineJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void insert(long settlementId, String type, String paymentKey,
                       BigDecimal amount, String eventId, Instant occurredAt) {
        jpa.insertLine(settlementId, type, paymentKey, amount, eventId, occurredAt);
    }

    @Override
    public List<SettlementLine> findBySettlementId(long settlementId) {
        return jpa.findBySettlementIdOrderByOccurredAtAscIdAsc(settlementId).stream()
            .map(SettlementLineJpaEntity::toDomain)
            .toList();
    }
}
