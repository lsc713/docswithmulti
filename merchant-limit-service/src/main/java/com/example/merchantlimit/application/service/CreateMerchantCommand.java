package com.example.merchantlimit.application.service;

import java.math.BigDecimal;

public record CreateMerchantCommand(
    String merchantKey,
    String name,
    int cancelPeriodDays,
    BigDecimal dailyLimit
) {}
