package com.example.riskmanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageJpaRepository
    extends JpaRepository<MerchantCancelUsageJpaEntity, Long> {

    Optional<MerchantCancelUsageJpaEntity> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);
}
