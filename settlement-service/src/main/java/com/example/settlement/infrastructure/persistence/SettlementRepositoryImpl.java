package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.domain.entity.Settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class SettlementRepositoryImpl implements SettlementRepository {

    private final SettlementJpaRepository jpa;

    public SettlementRepositoryImpl(SettlementJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public int ensureRow(long merchantId, LocalDate periodStart, LocalDate periodEnd) {
        return jpa.ensureRow(merchantId, periodStart, periodEnd);
    }

    @Override
    public long findId(long merchantId, LocalDate periodStart) {
        Long id = jpa.findIdByMerchantIdAndPeriodStart(merchantId, periodStart);
        if (id == null) {
            throw new IllegalStateException(
                "ensureRow 이후 settlement 헤더를 찾을 수 없음. merchantId=" + merchantId + ", periodStart=" + periodStart);
        }
        return id;
    }

    @Override
    public Optional<String> findStatus(long merchantId, LocalDate periodStart) {
        return Optional.ofNullable(jpa.findStatusByMerchantIdAndPeriodStart(merchantId, periodStart));
    }

    @Override
    public int addCancelAmount(long merchantId, LocalDate periodStart, BigDecimal amount) {
        return jpa.addCancelAmount(merchantId, periodStart, amount);
    }

    @Override
    public int addGrossAmount(long merchantId, LocalDate periodStart, BigDecimal amount) {
        return jpa.addGrossAmount(merchantId, periodStart, amount);
    }

    @Override
    public List<Settlement> findByMerchant(long merchantId, String status) {
        List<SettlementJpaEntity> rows = (status == null)
            ? jpa.findByMerchantIdOrderByPeriodStartDesc(merchantId)
            : jpa.findByMerchantIdAndStatusOrderByPeriodStartDesc(merchantId, status);
        return rows.stream().map(SettlementJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Settlement> findById(long id) {
        return jpa.findById(id).map(SettlementJpaEntity::toDomain);
    }

    @Override
    public List<Settlement> findFinalizable(LocalDate cutoff) {
        return jpa.findByStatusAndPeriodEndBefore("OPEN", cutoff).stream()
            .map(SettlementJpaEntity::toDomain)
            .toList();
    }

    @Override
    public int finalizeOpen(long id, BigDecimal fee, BigDecimal vat, BigDecimal net) {
        return jpa.finalizeOpen(id, fee, vat, net);
    }
}
