package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.Payment;

import java.util.Optional;

/**
 * PaymentRepository 구현체
 */
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    public PaymentRepositoryImpl(PaymentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Payment> findByPaymentKey(String paymentKey) {
        return jpaRepository.findByPaymentKey(paymentKey)
            .map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = PaymentJpaEntity.from(payment);
        PaymentJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }
}
