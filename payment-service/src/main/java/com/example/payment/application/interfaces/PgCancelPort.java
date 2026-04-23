package com.example.payment.application.interfaces;

import com.example.payment.application.dto.PgCancelResult;
import java.math.BigDecimal;

public interface PgCancelPort {
    PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason);
}
