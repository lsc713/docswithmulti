package com.example.payment.presentation.dto;

import com.example.payment.application.usecase.PaymentSettlementQuery.PaymentSettlementView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /v1/payments/settlement 응답 (RECON-03).
 *
 * createdAt/completedAt은 ISO-8601 UTC(trailing Z)로 직렬화된다 — 정산 서비스(03-02) HTTP 클라이언트가
 * Instant.parse로 byte-for-byte 미러링하는 wire contract.
 */
public record PaymentSettlementResponse(
    String paymentKey,
    long merchantId,
    BigDecimal totalAmount,
    String status,
    Instant createdAt,
    List<Cancel> cancels
) {
    public record Cancel(long cancelRequestId, BigDecimal cancelAmount, Instant completedAt) {}

    public static PaymentSettlementResponse from(PaymentSettlementView v) {
        return new PaymentSettlementResponse(
            v.paymentKey(),
            v.merchantId(),
            v.totalAmount(),
            v.status(),
            v.createdAt(),
            v.cancels().stream()
                .map(c -> new Cancel(c.cancelRequestId(), c.cancelAmount(), c.completedAt()))
                .toList()
        );
    }
}
