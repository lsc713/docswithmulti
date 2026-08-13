package com.example.payment.application.interfaces;

import java.math.BigDecimal;

public interface TossPaymentPort {
    void confirm(String paymentKey, String paymentRequestId, BigDecimal amount);
    Status getStatus(String paymentKey);

    enum Status { DONE, ABORTED, EXPIRED, PENDING }
}
