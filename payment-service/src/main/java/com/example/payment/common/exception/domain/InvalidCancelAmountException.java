package com.example.payment.common.exception.domain;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 취소 금액이 1원 미만일 때 발생
 */
@Getter
public class InvalidCancelAmountException extends BusinessException {

    private final BigDecimal cancelAmount;

    public InvalidCancelAmountException(BigDecimal cancelAmount) {
        super(
            ErrorCode.INVALID_CANCEL_AMOUNT,
            String.format("취소 금액은 1원 이상이어야 합니다. (요청: %s)", cancelAmount)
        );
        this.cancelAmount = cancelAmount;
    }

}
