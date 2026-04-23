package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.MerchantCancelUsageRepository;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.Optional;

@RequiredArgsConstructor
public class MerchantCancelUsageRepositoryImpl implements MerchantCancelUsageRepository {

    private final MerchantCancelUsageJpaRepository jpa;

    @Override
    public MerchantCancelUsage save(MerchantCancelUsage usage) {
        return jpa.save(MerchantCancelUsageJpaEntity.from(usage)).toDomain();
    }

    @Override
    public Optional<MerchantCancelUsage> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate) {
        return jpa.findByMerchantIdAndKstDate(merchantId, kstDate)
            .map(MerchantCancelUsageJpaEntity::toDomain);
    }

    @Override
    public Optional<MerchantCancelUsage> findByMerchantIdAndKstDateForUpdate(long merchantId, LocalDate kstDate) {
        return jpa.findByMerchantIdAndKstDateForUpdate(merchantId, kstDate)
            .map(MerchantCancelUsageJpaEntity::toDomain);
    }
}
