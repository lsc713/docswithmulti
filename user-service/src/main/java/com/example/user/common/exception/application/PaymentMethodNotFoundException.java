package com.example.user.common.exception.application;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class PaymentMethodNotFoundException extends BusinessException {
    public PaymentMethodNotFoundException(long paymentMethodId) {
        super(ErrorCode.PAYMENT_METHOD_NOT_FOUND, "결제수단을 찾을 수 없습니다. id=" + paymentMethodId);
    }
}
