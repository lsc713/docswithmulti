package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LimitHistoryJpaRepository
    extends JpaRepository<LimitHistoryJpaEntity, Long> {
    Page<LimitHistoryJpaEntity> findByMerchantIdOrderByCreatedAtDesc(
        Long merchantId, Pageable pageable);
}
