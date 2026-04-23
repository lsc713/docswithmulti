package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.domain.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public class MerchantRepositoryImpl implements MerchantRepository {

    private final MerchantJpaRepository jpaRepository;

    public MerchantRepositoryImpl(MerchantJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Merchant save(Merchant merchant) {
        MerchantJpaEntity entity = MerchantJpaEntity.from(merchant);
        return jpaRepository.save(entity).toDomain();
    }

    @Override
    public Optional<Merchant> findById(long id) {
        return jpaRepository.findById(id).map(MerchantJpaEntity::toDomain);
    }

    @Override
    public Optional<Merchant> findByMerchantKey(String merchantKey) {
        return jpaRepository.findByMerchantKey(merchantKey).map(MerchantJpaEntity::toDomain);
    }

    @Override
    public boolean existsByMerchantKey(String merchantKey) {
        return jpaRepository.existsByMerchantKey(merchantKey);
    }

    @Override
    public Page<Merchant> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable).map(MerchantJpaEntity::toDomain);
    }
}
