package com.example.riskmanagement.application.interfaces;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface MerchantLimitClient {
    /** merchant-limit-service HTTP 호출. 실패 시 ServiceUnavailableException. */
    BigDecimal fetchDailyLimit(long merchantId, LocalDate kstDate);
}
