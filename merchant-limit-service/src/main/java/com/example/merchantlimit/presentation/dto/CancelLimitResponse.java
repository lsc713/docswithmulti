package com.example.merchantlimit.presentation.dto;

import java.math.BigDecimal;

public record CancelLimitResponse(
    long merchantId, BigDecimal dailyLimit, String merchantStatus
) {}
