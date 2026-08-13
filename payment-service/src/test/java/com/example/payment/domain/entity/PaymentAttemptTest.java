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

    @Test
    void only_unattached_pending_attempt_can_fail_from_browser_callback() {
        Payment payment = Payment.pendingAttempt(
            "4d36e967-e325-11ce-bfc1-08002be10318",
            1L, 2L, "NORMAL", BigDecimal.TEN, "KRW", 90, 7L);

        assertThat(payment.failUnconfirmed()).isTrue();
        assertThat(payment.failUnconfirmed()).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        Payment attached = Payment.pendingAttempt(
            "5d36e967-e325-11ce-bfc1-08002be10318",
            1L, 2L, "NORMAL", BigDecimal.TEN, "KRW", 90, 7L);
        attached.attachPaymentKey("toss_key");

        assertThat(attached.failUnconfirmed()).isFalse();
        assertThat(attached.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
}
