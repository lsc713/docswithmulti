package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.PaymentStatus;
import lombok.Getter;

/**
 * Payment 상태가 취소 불가능한 상태일 때 발생
 *
 * 거부 상태: PENDING, CANCELLED, CANCEL_FAILED
 */
@Getter
public class InvalidPaymentStatusException extends BusinessException {

    private final PaymentStatus currentStatus;

    public InvalidPaymentStatusException(PaymentStatus currentStatus) {
        super(
            ErrorCode.INVALID_PAYMENT_STATUS,
            String.format("현재 결제 상태(%s)에서는 취소할 수 없습니다.", currentStatus.name())
        );
        this.currentStatus = currentStatus;
    }

}
