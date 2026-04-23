package com.example.merchantlimit.domain.exception;

import com.example.merchantlimit.common.exception.BusinessException;
import com.example.merchantlimit.common.exception.ErrorCode;

import java.math.BigDecimal;

public class InvalidLimitAmountException extends BusinessException {
    public InvalidLimitAmountException(BigDecimal amount) {
        super(ErrorCode.INVALID_LIMIT_AMOUNT,
            "한도는 1원 이상이어야 합니다. 요청값=" + amount.toPlainString());
    }
}
