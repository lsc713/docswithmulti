package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantCancelLimitJpaRepository
    extends JpaRepository<MerchantCancelLimitJpaEntity, Long> {
    Optional<MerchantCancelLimitJpaEntity> findByMerchantId(Long merchantId);
}
