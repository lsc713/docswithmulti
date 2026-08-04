package com.example.settlement.presentation.dto;

import java.math.BigDecimal;

/** PUT /v1/settlements/config/{merchantId} 본문. feeRate 검증은 서비스에서(0 ≤ rate < 1, scale ≤ 4). */
public record SettlementConfigRequest(BigDecimal feeRate) {}
