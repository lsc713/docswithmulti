package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.ReserveRepository;
import com.example.settlement.domain.entity.Reserve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public class ReserveRepositoryImpl implements ReserveRepository {

    private final ReserveJpaRepository jpa;

    public ReserveRepositoryImpl(ReserveJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public BigDecimal currentHeld(long merchantId) {
        return jpa.currentHeld(merchantId);
    }

    @Override
    public void insertHeld(long settlementId, long merchantId, BigDecimal amount,
                           LocalDate holdUntil, String transferRef) {
        jpa.insertHeld(settlementId, merchantId, amount, holdUntil, transferRef);
    }

    @Override
    public Optional<Reserve> findBySettlementId(long settlementId) {
        return jpa.findBySettlementId(settlementId).map(ReserveJpaEntity::toDomain);
    }
}
