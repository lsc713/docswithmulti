package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * PaymentItem의 취소 가능액을 초과했을 때 발생
 *
 * cancelAmount > (item_amount - cancelled_amount)인 경우
 */
@Getter
public class CancelAmountExceededException extends BusinessException {

    private final long paymentItemId;
    private final BigDecimal requestedAmount;
    private final BigDecimal availableAmount;

    public CancelAmountExceededException(
        long paymentItemId,
        BigDecimal requestedAmount,
        BigDecimal availableAmount
    ) {
        super(
            ErrorCode.CANCEL_AMOUNT_EXCEEDED,
            String.format(
                "결제 항목 %d의 취소 가능액(%.2f원)을 초과했습니다. (요청: %.2f원)",
                paymentItemId, availableAmount, requestedAmount
            )
        );
        this.paymentItemId = paymentItemId;
        this.requestedAmount = requestedAmount;
        this.availableAmount = availableAmount;
    }

}
