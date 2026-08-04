package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.domain.entity.SettlementLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Override
    public Map<String, BigDecimal> sumLinesByType(long settlementId) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Object[] row : jpa.sumByType(settlementId)) {
            sums.put((String) row[0], new BigDecimal(row[1].toString())); // JDBC 숫자타입 편차 방어
        }
        return sums;
    }
}
