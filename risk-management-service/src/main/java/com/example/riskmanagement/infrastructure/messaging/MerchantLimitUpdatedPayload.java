package com.example.riskmanagement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MerchantLimitUpdatedPayload(long merchantId, BigDecimal newLimit, LocalDate kstDate) {}
