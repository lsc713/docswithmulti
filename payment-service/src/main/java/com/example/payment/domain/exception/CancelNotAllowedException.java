package com.example.payment.domain.exception;

import com.example.payment.domain.entity.PaymentStatus;

/**
 * 취소 불가능한 상태에서 취소를 시도했을 때 발생
 *
 * 현재 Payment 상태가 PENDING, CANCELLED, CANCEL_FAILED인 경우
 *
 * 대응 에러코드: INVALID_PAYMENT_STATUS
 * HTTP 상태: 422 (비즈니스 규칙 위반)
 */
public class CancelNotAllowedException extends DomainException {

    private final PaymentStatus currentStatus;

    public CancelNotAllowedException(PaymentStatus currentStatus) {
        super(
            "INVALID_PAYMENT_STATUS",
            String.format("현재 결제 상태(%s)에서는 취소할 수 없습니다", currentStatus.name())
        );
        this.currentStatus = currentStatus;
    }

    public PaymentStatus getCurrentStatus() {
        return currentStatus;
    }
}
