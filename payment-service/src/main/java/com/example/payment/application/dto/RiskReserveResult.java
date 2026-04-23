package com.example.payment.application.dto;

import java.math.BigDecimal;

public record RiskReserveResult(
    long merchantId,
    BigDecimal dailyLimit,
    BigDecimal usedAmount,
    BigDecimal remainingLimit
) {}
