package com.example.payment.domain.policy;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.common.exception.domain.InvalidPaymentStatusException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentStatusPolicy 테스트")
class PaymentStatusPolicyTest {

    @Test
    @DisplayName("Payment 상태가 COMPLETED이면 검증 통과")
    void should_validate_when_status_is_completed() {
        Payment payment = Payment.of(
            "pay_123", 1L, 10L, "CREDIT_CARD",
            BigDecimal.valueOf(10000), "KRW", 90
        );

        assertDoesNotThrow(
            () -> PaymentStatusPolicy.validateCancellableStatus(payment)
        );
    }

    @Test
    @DisplayName("Payment 상태가 PARTIAL_CANCELLED이면 검증 통과")
    void should_validate_when_status_is_partial_cancelled() {
        Payment payment = Payment.of(
            "pay_123", 1L, 10L, "CREDIT_CARD",
            BigDecimal.valueOf(10000), "KRW", 90
        );
        payment.updateStatus(PaymentStatus.PARTIAL_CANCELLED);

        assertDoesNotThrow(
            () -> PaymentStatusPolicy.validateCancellableStatus(payment)
        );
    }

    @Test
    @DisplayName("Payment 상태가 PENDING이면 InvalidPaymentStatusException 발생")
    void should_reject_when_status_is_pending() {
        Payment payment = Payment.ofPending(
            "pay_123", 1L, 10L, "CREDIT_CARD",
            BigDecimal.valueOf(10000), "KRW", 90
        );

        InvalidPaymentStatusException exception = assertThrows(
            InvalidPaymentStatusException.class,
            () -> PaymentStatusPolicy.validateCancellableStatus(payment)
        );
        assertEquals(PaymentStatus.PENDING, exception.getCurrentStatus());
    }

    @Test
    @DisplayName("Payment 상태가 CANCELLED이면 InvalidPaymentStatusException 발생")
    void should_reject_when_status_is_cancelled() {
        Payment payment = Payment.of(
            "pay_123", 1L, 10L, "CREDIT_CARD",
            BigDecimal.valueOf(10000), "KRW", 90
        );
        payment.updateStatus(PaymentStatus.CANCELLED);

        InvalidPaymentStatusException exception = assertThrows(
            InvalidPaymentStatusException.class,
            () -> PaymentStatusPolicy.validateCancellableStatus(payment)
        );
        assertEquals(PaymentStatus.CANCELLED, exception.getCurrentStatus());
    }

    @Test
    @DisplayName("Payment 상태가 CANCEL_FAILED이면 InvalidPaymentStatusException 발생")
    void should_reject_when_status_is_cancel_failed() {
        Payment payment = Payment.of(
            "pay_123", 1L, 10L, "CREDIT_CARD",
            BigDecimal.valueOf(10000), "KRW", 90
        );
        payment.updateStatus(PaymentStatus.CANCEL_FAILED);

        InvalidPaymentStatusException exception = assertThrows(
            InvalidPaymentStatusException.class,
            () -> PaymentStatusPolicy.validateCancellableStatus(payment)
        );
        assertEquals(PaymentStatus.CANCEL_FAILED, exception.getCurrentStatus());
    }
}
