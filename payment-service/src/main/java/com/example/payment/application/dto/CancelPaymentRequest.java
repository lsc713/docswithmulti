package com.example.payment.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 취소 요청 DTO
 *
 * domain-rules.md 2-3: 검증 규칙
 * - cancelAmount >= 1
 * - cancelItems 길이 > 0
 * - cancelItems 중복 없음
 * - sum(cancelItems.cancelAmount) == cancelAmount
 */
public record CancelPaymentRequest(
    BigDecimal cancelAmount,
    String cancelReason,
    List<CancelItemRequest> cancelItems
) {

    public record CancelItemRequest(
        Long paymentItemId,
        BigDecimal cancelAmount
    ) {
    }
}
