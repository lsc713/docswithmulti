package com.example.payment.infrastructure.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Toss Payments 응답의 cancels[] 원소 (일부 필드만 매핑). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCancel(
    BigDecimal cancelAmount,
    String cancelReason,
    BigDecimal taxFreeAmount,
    BigDecimal refundableAmount,
    String transactionKey,
    String canceledAt,
    String cancelStatus
) {
}
