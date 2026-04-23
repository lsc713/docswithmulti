package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;

import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageRepository {
    MerchantCancelUsage save(MerchantCancelUsage usage);
    Optional<MerchantCancelUsage> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);
    Optional<MerchantCancelUsage> findByMerchantIdAndKstDateForUpdate(long merchantId, LocalDate kstDate);
}
