package com.example.payment.domain.exception;

import java.math.BigDecimal;

/**
 * 취소 금액이 1원 미만일 때 발생
 *
 * 대응하는 HTTP 상태코드: 400 INVALID_CANCEL_AMOUNT
 * (error-catalog.md 참조)
 */
public class InvalidCancelAmountException extends DomainException {

    private final BigDecimal cancelAmount;

    public InvalidCancelAmountException(BigDecimal cancelAmount) {
        super(
            "INVALID_CANCEL_AMOUNT",
            String.format("취소 금액은 1원 이상이어야 합니다. (요청: %s)", cancelAmount)
        );
        this.cancelAmount = cancelAmount;
    }

    public BigDecimal getCancelAmount() {
        return cancelAmount;
    }
}
