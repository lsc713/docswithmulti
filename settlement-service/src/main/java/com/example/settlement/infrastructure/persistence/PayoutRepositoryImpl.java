package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.PayoutRepository;
import com.example.settlement.domain.entity.Payout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class PayoutRepositoryImpl implements PayoutRepository {

    private final PayoutJpaRepository jpa;

    public PayoutRepositoryImpl(PayoutJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Payout insertProcessing(long settlementId, long merchantId, BigDecimal amount, String transferRef) {
        PayoutJpaEntity saved = jpa.save(
            PayoutJpaEntity.newProcessing(settlementId, merchantId, amount, transferRef, Instant.now()));
        return saved.toDomain();
    }

    @Override
    public Optional<Payout> findBySettlementId(long settlementId) {
        return jpa.findBySettlementId(settlementId).map(PayoutJpaEntity::toDomain);
    }

    @Override
    public Optional<Payout> findByTransferRef(String transferRef) {
        return jpa.findByTransferRef(transferRef).map(PayoutJpaEntity::toDomain);
    }

    @Override
    public List<Payout> findStuckProcessing(Instant cutoff) {
        return jpa.findByStatusAndUpdatedAtBefore("PROCESSING", cutoff)
            .stream().map(PayoutJpaEntity::toDomain).toList();
    }

    @Override
    public int applyResult(String transferRef, String result, String err) {
        return jpa.applyResult(transferRef, result, err);
    }
}
