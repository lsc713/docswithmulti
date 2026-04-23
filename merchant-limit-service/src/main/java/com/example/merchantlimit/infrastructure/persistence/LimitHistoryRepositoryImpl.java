package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.application.interfaces.LimitHistoryRepository;
import com.example.merchantlimit.domain.entity.LimitHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public class LimitHistoryRepositoryImpl implements LimitHistoryRepository {

    private final LimitHistoryJpaRepository jpaRepository;

    public LimitHistoryRepositoryImpl(LimitHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(LimitHistory history) {
        jpaRepository.save(LimitHistoryJpaEntity.from(history));
    }

    @Override
    public Page<LimitHistory> findByMerchantId(long merchantId, Pageable pageable) {
        return jpaRepository
            .findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
            .map(LimitHistoryJpaEntity::toDomain);
    }
}
