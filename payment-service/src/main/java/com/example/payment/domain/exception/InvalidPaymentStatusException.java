package com.example.payment.domain.exception;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.PaymentStatus;

/**
 * Payment 상태가 취소 불가능한 상태일 때 발생
 *
 * 거부 상태: PENDING, CANCELLED, CANCEL_FAILED
 */
public class InvalidPaymentStatusException extends DomainException {

    private final PaymentStatus currentStatus;

    public InvalidPaymentStatusException(PaymentStatus currentStatus) {
        super(
            ErrorCode.INVALID_PAYMENT_STATUS,
            String.format("현재 결제 상태(%s)에서는 취소할 수 없습니다.", currentStatus.name())
        );
        this.currentStatus = currentStatus;
    }

    public PaymentStatus getCurrentStatus() {
        return currentStatus;
    }
}
