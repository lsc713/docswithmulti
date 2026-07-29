package com.example.payment.infrastructure.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * Toss Payments 결제 조회/취소 응답 (일부 필드만 매핑).
 * 실제 Toss 응답은 이보다 필드가 훨씬 많음 — ignoreUnknown으로 무시.
 *
 * GET /v1/payments/{paymentKey}, POST /v1/payments/{paymentKey}/cancel 공통 응답 형태.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentResponse(
    String status,
    BigDecimal balanceAmount,
    List<TossCancel> cancels
) {
}
