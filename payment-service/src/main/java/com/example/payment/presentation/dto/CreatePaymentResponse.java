package com.example.payment.presentation.dto;

import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.domain.entity.PaymentItem;

import java.math.BigDecimal;
import java.util.List;

public record CreatePaymentResponse(
    long paymentId,
    String paymentKey,
    BigDecimal totalAmount,
    String status,
    List<PaymentItemResponse> items
) {
    public record PaymentItemResponse(long paymentItemId, String itemName, BigDecimal itemAmount) {}

    public static CreatePaymentResponse from(Result result) {
        List<PaymentItemResponse> itemResponses = result.items().stream()
            .map(item -> new PaymentItemResponse(item.getId(), item.getItemName(), item.getItemAmount()))
            .toList();
        return new CreatePaymentResponse(
            result.payment().getId(),
            result.payment().getPaymentKey(),
            result.payment().getTotalAmount(),
            result.payment().getStatus().name(),
            itemResponses
        );
    }
}
