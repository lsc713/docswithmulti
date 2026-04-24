package com.example.riskmanagement.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageJpaRepository
    extends JpaRepository<MerchantCancelUsageJpaEntity, Long> {

    Optional<MerchantCancelUsageJpaEntity> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM MerchantCancelUsageJpaEntity e WHERE e.merchantId = :merchantId AND e.kstDate = :kstDate")
    Optional<MerchantCancelUsageJpaEntity> findByMerchantIdAndKstDateForUpdate(long merchantId, LocalDate kstDate);
}
