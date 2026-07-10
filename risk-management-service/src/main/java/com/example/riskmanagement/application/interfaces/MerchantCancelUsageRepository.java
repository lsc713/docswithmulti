package com.example.riskmanagement.application.interfaces;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;

import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageRepository {
    MerchantCancelUsage save(MerchantCancelUsage usage);
    Optional<MerchantCancelUsage> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);
    Optional<MerchantCancelUsage> findByMerchantIdAndKstDateForUpdate(long merchantId, LocalDate kstDate);

    /** (merchant_id, kst_date) 행이 없으면 used=0 으로 생성(멱등). 있으면 no-op. */
    void ensureRow(long merchantId, LocalDate kstDate, java.math.BigDecimal dailyLimit);

    /**
     * 원자 조건부 차감. {@code used_amount + amount <= daily_limit} 일 때만 증가.
     * 규칙 원본은 {@link com.example.riskmanagement.domain.service.CancelLimitDomainService#canDeduct}.
     * @return 1 = 차감 성공, 0 = 한도 초과(변경 없음)
     */
    int tryDeduct(long merchantId, LocalDate kstDate, java.math.BigDecimal amount);
}
