package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.PaymentStatus;
import lombok.Getter;

/**
 * 취소 불가능한 상태에서 취소를 시도했을 때 발생
 *
 * 현재 Payment 상태가 PENDING, CANCELLED, CANCEL_FAILED인 경우
 */
@Getter
public class CancelNotAllowedException extends BusinessException {

    private final PaymentStatus currentStatus;

    public CancelNotAllowedException(PaymentStatus currentStatus) {
        super(
            ErrorCode.INVALID_PAYMENT_STATUS,
            String.format("현재 결제 상태(%s)에서는 취소할 수 없습니다", currentStatus.name())
        );
        this.currentStatus = currentStatus;
    }

}
