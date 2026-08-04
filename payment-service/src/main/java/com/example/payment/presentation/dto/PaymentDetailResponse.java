package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;
import java.util.List;

/** 주문내역 상세 (P3). 본인 소유 아니면 컨트롤러 진입 전 404(존재 은닉) — PaymentHistoryService 참고. */
public record PaymentDetailResponse(
    String paymentKey, BigDecimal totalAmount, String status, String createdAt,
    long orderId, String pgType, List<PaymentSummaryResponse.Item> items
) {
    public static PaymentDetailResponse from(Payment p, List<PaymentItem> items) {
        return new PaymentDetailResponse(
            p.getPaymentKey(), p.getTotalAmount(), p.getStatus().name(), p.getCreatedAt().toString(),
            p.getOrderId(), p.getPgType(),
            items.stream().map(i -> new PaymentSummaryResponse.Item(i.getId(), i.getItemName(), i.getItemAmount(), i.getStatus().name())).toList());
    }
}
