package com.example.payment.domain.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.PaymentItemStatus;
import lombok.Getter;

/**
 * PaymentItem 상태가 취소 불가능한 상태일 때 발생
 *
 * domain-rules.md 1-2: PaymentItem 상태 조건
 * CANCELLED 상태인 PaymentItem을 포함한 요청은 전액 거부한다.
 */
@Getter
public class InvalidPaymentItemStatusException extends BusinessException {

    private final long paymentItemId;
    private final PaymentItemStatus currentStatus;

    public InvalidPaymentItemStatusException(long paymentItemId, PaymentItemStatus currentStatus) {
        super(
            ErrorCode.INVALID_PAYMENT_ITEM_STATUS,
            String.format(
                "PaymentItem(id=%d)의 상태(%s)에서는 취소할 수 없습니다.",
                paymentItemId,
                currentStatus.name()
            )
        );
        this.paymentItemId = paymentItemId;
        this.currentStatus = currentStatus;
    }

}
