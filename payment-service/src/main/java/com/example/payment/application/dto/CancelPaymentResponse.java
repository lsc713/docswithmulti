package com.example.payment.application.dto;

import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.CancellerType;
import com.example.payment.domain.entity.PaymentItemStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 취소 응답 DTO
 *
 * API 스펙: api-spec.md 1 "취소 요청" Response 200
 */
public record CancelPaymentResponse(
    String cancelRequestId,
    String paymentKey,
    BigDecimal cancelAmount,
    String currency,
    String status,
    String cancellerType,
    String cancelReason,
    List<CancelledItem> cancelledItems,
    Instant completedAt
) {

    public record CancelledItem(
        Long paymentItemId,
        BigDecimal cancelAmount,
        String status
    ) {
    }
}
