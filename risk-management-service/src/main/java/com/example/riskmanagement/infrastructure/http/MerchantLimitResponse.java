package com.example.riskmanagement.infrastructure.http;

import java.math.BigDecimal;

public record MerchantLimitResponse(long merchantId, BigDecimal dailyLimit, String merchantStatus) {}
