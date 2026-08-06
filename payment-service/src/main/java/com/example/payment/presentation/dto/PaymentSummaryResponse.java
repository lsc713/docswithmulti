package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;
import java.util.List;

/** 주문내역 목록 항목 (P3). */
public record PaymentSummaryResponse(
    String paymentKey, BigDecimal totalAmount, String status, String createdAt,
    long orderId, List<Item> items, String cancelRequestStatus
) {
    public record Item(long paymentItemId, String itemName, BigDecimal itemAmount, String status) {}

    public static PaymentSummaryResponse from(Payment p, List<PaymentItem> items, String cancelRequestStatus) {
        return new PaymentSummaryResponse(
            p.getPaymentKey(), p.getTotalAmount(), p.getStatus().name(),
            p.getCreatedAt().toString(), p.getOrderId(),
            items.stream().map(i -> new Item(i.getId(), i.getItemName(), i.getItemAmount(), i.getStatus().name())).toList(),
            cancelRequestStatus);
    }
}
