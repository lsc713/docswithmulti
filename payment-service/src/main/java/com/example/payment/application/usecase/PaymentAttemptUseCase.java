package com.example.payment.application.usecase;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.domain.entity.PaymentStatus;

import java.math.BigDecimal;

public interface PaymentAttemptUseCase {
    Prepared prepare(CreatePaymentCommand command);
    Status confirm(String paymentRequestId, long userId, String paymentKey, String orderId, BigDecimal amount);
    Status fail(String paymentRequestId, long userId);
    Status get(String paymentRequestId, long userId);

    record Prepared(
        String paymentRequestId, BigDecimal amount, String orderName,
        String customerKey, String clientKey
    ) {}

    record Status(
        String paymentRequestId, String paymentKey, BigDecimal amount, PaymentStatus status
    ) {}
}
