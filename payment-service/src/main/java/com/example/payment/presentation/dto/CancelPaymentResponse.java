package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.CancelRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CancelPaymentResponse(
    Long cancelRequestId,
    String paymentKey,
    BigDecimal cancelAmount,
    String status,
    List<CancelledItemResponse> cancelledItems,
    Instant completedAt
) {
    public static CancelPaymentResponse of(
        CancelRequest cancelRequest, String paymentKey, List<CancelledItemResponse> cancelledItems
    ) {
        return new CancelPaymentResponse(
            cancelRequest.getId(),
            paymentKey,
            cancelRequest.getCancelAmount(),
            cancelRequest.getStatus().name(),
            cancelledItems,
            cancelRequest.getCompletedAt()
        );
    }
}
