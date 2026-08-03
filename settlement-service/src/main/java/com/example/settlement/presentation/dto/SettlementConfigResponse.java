package com.example.settlement.presentation.dto;

import java.math.BigDecimal;

public record SettlementConfigResponse(long merchantId, BigDecimal feeRate) {}
