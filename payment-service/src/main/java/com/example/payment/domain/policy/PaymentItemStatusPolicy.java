package com.example.payment.domain.policy;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.common.exception.domain.InvalidPaymentItemStatusException;

/**
 * PaymentItem 상태 검증 정책 객체
 *
 * domain-rules.md 1-2: PaymentItem 상태 조건
 * - 허용 상태: ACTIVE, PARTIAL_CANCELLED
 * - 거부 상태: CANCELLED
 */
public class PaymentItemStatusPolicy {

    private PaymentItemStatusPolicy() {
    }

    /**
     * PaymentItem 상태가 취소 가능한 상태인지 검증
     *
     * 허용 상태: ACTIVE, PARTIAL_CANCELLED
     * 거부 상태: CANCELLED
     *
     * @param item 결제 항목
     * @throws InvalidPaymentItemStatusException 취소 불가능한 상태일 때
     */
    public static void validateCancellableStatus(PaymentItem item) {
        if (!item.isCancellable()) {
            throw new InvalidPaymentItemStatusException(
                item.getOrderItemId(),
                item.getStatus()
            );
        }
    }
}
