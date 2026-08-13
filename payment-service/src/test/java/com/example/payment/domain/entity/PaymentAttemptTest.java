package com.example.payment.domain.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptTest {

    @Test
    void pending_attempt_links_pg_key_then_completes_once() {
        Payment payment = Payment.pendingAttempt(
            "4d36e967-e325-11ce-bfc1-08002be10318",
            1L, 2L, "NORMAL", BigDecimal.valueOf(20_000), "KRW", 90, 7L);

        assertThat(payment.getPaymentKey()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        payment.attachPaymentKey("toss_key");
        assertThat(payment.complete()).isTrue();
        assertThat(payment.complete()).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void different_pg_key_cannot_replace_attached_key() {
        Payment payment = Payment.pendingAttempt(
            "4d36e967-e325-11ce-bfc1-08002be10318",
            1L, 2L, "NORMAL", BigDecimal.TEN, "KRW", 90, 7L);
        payment.attachPaymentKey("first");

        assertThatThrownBy(() -> payment.attachPaymentKey("second"))
            .isInstanceOf(IllegalStateException.class);
    }
}
