package com.example.riskmanagement.application.interfaces;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface DailyLimitCache {
    Optional<BigDecimal> get(long merchantId, LocalDate kstDate);
    void set(long merchantId, LocalDate kstDate, BigDecimal limit);
}
