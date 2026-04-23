package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.PaymentItem;

import java.math.BigDecimal;

public record CancelledItemResponse(
    long paymentItemId,
    BigDecimal itemAmount,
    String status
) {
    public static CancelledItemResponse from(PaymentItem item) {
        return new CancelledItemResponse(item.getId(), item.getItemAmount(), item.getStatus().name());
    }
}
