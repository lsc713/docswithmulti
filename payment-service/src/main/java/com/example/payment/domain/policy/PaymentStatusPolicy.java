package com.example.payment.domain.policy;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.exception.InvalidPaymentStatusException;

/**
 * Payment 상태 검증 정책 객체
 *
 * domain-rules.md 1-1: Payment 상태 조건
 */
public class PaymentStatusPolicy {

    private PaymentStatusPolicy() {
    }

    /**
     * Payment 상태가 취소 가능한 상태인지 검증
     *
     * 허용 상태: COMPLETED, PARTIAL_CANCELLED
     * 거부 상태: PENDING, CANCELLED, CANCEL_FAILED
     *
     * @param payment 결제 정보
     * @throws InvalidPaymentStatusException 취소 불가능한 상태일 때
     */
    public static void validateCancellableStatus(Payment payment) {
        if (!payment.canBeCancelled()) {
            throw new InvalidPaymentStatusException(payment.getStatus());
        }
    }
}
