package com.example.settlement.presentation.dto;

import java.math.BigDecimal;

/** 유보 정책 조회/설정 응답 (RCFG-01/02). */
public record ReserveConfigResponse(long merchantId, BigDecimal reserveRate, BigDecimal reserveCap, int holdDays) {}
