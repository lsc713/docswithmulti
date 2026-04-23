package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;

import java.util.Optional;

public class MerchantCancelLimitRepositoryImpl implements MerchantCancelLimitRepository {

    private final MerchantCancelLimitJpaRepository jpaRepository;

    public MerchantCancelLimitRepositoryImpl(MerchantCancelLimitJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantCancelLimit save(MerchantCancelLimit limit) {
        return jpaRepository.save(MerchantCancelLimitJpaEntity.from(limit)).toDomain();
    }

    @Override
    public Optional<MerchantCancelLimit> findByMerchantId(long merchantId) {
        return jpaRepository.findByMerchantId(merchantId)
            .map(MerchantCancelLimitJpaEntity::toDomain);
    }
}
