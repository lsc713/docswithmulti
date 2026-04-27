package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.Payment;

import java.math.BigDecimal;

public record CreatePaymentResponse(
    long paymentId,
    String paymentKey,
    BigDecimal totalAmount,
    String status
) {
    public static CreatePaymentResponse from(Payment payment) {
        return new CreatePaymentResponse(
            payment.getId(),
            payment.getPaymentKey(),
            payment.getTotalAmount(),
            payment.getStatus().name()
        );
    }
}
