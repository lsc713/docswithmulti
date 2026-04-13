package com.example.payment.domain.exception;

import com.example.payment.common.exception.ErrorCode;
import java.math.BigDecimal;

/**
 * PaymentItem의 취소 가능액을 초과했을 때 발생
 *
 * cancelAmount > (item_amount - cancelled_amount)인 경우
 */
public class CancelAmountExceededException extends DomainException {

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

    public long getPaymentItemId() {
        return paymentItemId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getAvailableAmount() {
        return availableAmount;
    }
}
