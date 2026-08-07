package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.MerchantReserveConfigRepository;
import com.example.settlement.domain.entity.MerchantReserveConfig;

import java.math.BigDecimal;
import java.util.Optional;

public class MerchantReserveConfigRepositoryImpl implements MerchantReserveConfigRepository {

    private final MerchantReserveConfigJpaRepository jpa;

    public MerchantReserveConfigRepositoryImpl(MerchantReserveConfigJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<MerchantReserveConfig> findConfig(long merchantId) {
        return jpa.findByMerchantIdAndActiveTrue(merchantId)
            .map(MerchantReserveConfigJpaEntity::toDomain);
    }

    @Override
    public void upsert(long merchantId, BigDecimal reserveRate, BigDecimal reserveCap, int holdDays) {
        jpa.upsert(merchantId, reserveRate, reserveCap, holdDays);
    }
}
