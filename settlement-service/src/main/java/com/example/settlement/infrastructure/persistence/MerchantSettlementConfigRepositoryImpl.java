package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.MerchantSettlementConfigRepository;

import java.math.BigDecimal;
import java.util.Optional;

public class MerchantSettlementConfigRepositoryImpl implements MerchantSettlementConfigRepository {

    private final MerchantSettlementConfigJpaRepository jpa;

    public MerchantSettlementConfigRepositoryImpl(MerchantSettlementConfigJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<BigDecimal> findRate(long merchantId) {
        return jpa.findByMerchantIdAndActiveTrue(merchantId)
            .map(MerchantSettlementConfigJpaEntity::getFeeRate);
    }

    @Override
    public void upsert(long merchantId, BigDecimal feeRate) {
        jpa.upsert(merchantId, feeRate);
    }
}
