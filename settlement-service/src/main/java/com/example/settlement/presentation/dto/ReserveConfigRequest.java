package com.example.settlement.presentation.dto;

import java.math.BigDecimal;

/** PUT /v1/settlements/reserve-config/{merchantId} 본문. 검증은 서비스에서(0≤rate<1·scale≤4·cap≥0·holdDays≥0). */
public record ReserveConfigRequest(BigDecimal reserveRate, BigDecimal reserveCap, Integer holdDays) {}
