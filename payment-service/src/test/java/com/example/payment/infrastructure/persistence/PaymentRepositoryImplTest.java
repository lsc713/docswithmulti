package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class PaymentRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired
    private PaymentJpaRepository jpaRepository;

    @Test
    void should_save_and_find_payment_by_payment_key() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentJpaEntity entity = PaymentJpaEntity.from(payment);

        // When
        jpaRepository.save(entity);

        // Then
        Optional<PaymentJpaEntity> found = jpaRepository.findByPaymentKey(payment.getPaymentKey());
        assertThat(found).isPresent();
        assertThat(found.get().getPaymentKey()).isEqualTo(payment.getPaymentKey());
        assertThat(found.get().getTotalAmount()).isEqualTo(payment.getTotalAmount());
        assertThat(found.get().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void should_return_empty_when_payment_key_not_found() {
        // When & Then
        Optional<PaymentJpaEntity> found = jpaRepository.findByPaymentKey("pay_nonexistent");
        assertThat(found).isEmpty();
    }

    @Test
    void should_persist_status_update() {
        // Given
        Payment payment = PaymentFixture.completedPayment();
        PaymentJpaEntity entity = PaymentJpaEntity.from(payment);
        jpaRepository.save(entity);

        // When
        Optional<PaymentJpaEntity> found = jpaRepository.findByPaymentKey(payment.getPaymentKey());
        assertThat(found).isPresent();

        PaymentJpaEntity toUpdate = found.get();
        toUpdate.setStatus(PaymentStatus.PARTIAL_CANCELLED);
        toUpdate.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        jpaRepository.save(toUpdate);

        // Then
        Optional<PaymentJpaEntity> updated = jpaRepository.findByPaymentKey(payment.getPaymentKey());
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELLED);
    }

    @Test
    void should_map_all_fields_correctly() {
        // Given
        String paymentKey = "pay_test_mapping";
        Long merchantId = 123L;
        Long userId = 456L;
        String pgType = "TOSS";
        BigDecimal totalAmount = BigDecimal.valueOf(100000);
        String currency = "KRW";
        int cancelPeriodDays = 90;
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 14, 12, 0, 0);

        Payment payment = Payment.of(
            paymentKey, merchantId, userId, pgType, totalAmount, currency, cancelPeriodDays, createdAt
        );
        PaymentJpaEntity entity = PaymentJpaEntity.from(payment);

        // When
        jpaRepository.save(entity);

        // Then
        Optional<PaymentJpaEntity> found = jpaRepository.findByPaymentKey(paymentKey);
        assertThat(found).isPresent();
        PaymentJpaEntity actual = found.get();

        assertThat(actual.getPaymentKey()).isEqualTo(paymentKey);
        assertThat(actual.getMerchantId()).isEqualTo(merchantId);
        assertThat(actual.getUserId()).isEqualTo(userId);
        assertThat(actual.getPgType()).isEqualTo(pgType);
        assertThat(actual.getTotalAmount()).isEqualTo(totalAmount);
        assertThat(actual.getCurrency()).isEqualTo(currency);
        assertThat(actual.getCancelPeriodDays()).isEqualTo(cancelPeriodDays);
        assertThat(actual.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(actual.getCreatedAt()).isEqualTo(createdAt);
    }
}
