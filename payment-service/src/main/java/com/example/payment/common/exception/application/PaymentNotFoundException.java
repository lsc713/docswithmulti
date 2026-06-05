package com.example.payment.common.exception.application;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

/**
 * 결제를 찾을 수 없음
 *
 * error-catalog.md: PAYMENT_NOT_FOUND (404)
 */
public class PaymentNotFoundException extends BusinessException {

    private final String paymentKey;

    public PaymentNotFoundException(String paymentKey) {
        super(ErrorCode.PAYMENT_NOT_FOUND, "결제 정보를 찾을 수 없습니다.");
        this.paymentKey = paymentKey;
    }

    public String getPaymentKey() {
        return paymentKey;
    }
}
