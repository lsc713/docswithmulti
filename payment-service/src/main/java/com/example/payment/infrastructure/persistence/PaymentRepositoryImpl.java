package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

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
    public Optional<Payment> findByPaymentRequestId(String paymentRequestId) {
        return jpaRepository.findByPaymentRequestId(paymentRequestId)
            .map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByPaymentRequestIdForUpdate(String paymentRequestId) {
        return jpaRepository.findByPaymentRequestIdForUpdate(paymentRequestId)
            .map(PaymentJpaEntity::toDomain);
    }

    @Override
    public List<Payment> findPendingRecoveryCandidates(
        LocalDateTime expiresAt, LocalDateTime unknownAt, int size
    ) {
        return jpaRepository.findPendingRecoveryCandidates(
            expiresAt, unknownAt, PageRequest.of(0, size)).stream()
            .map(PaymentJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsByPaymentKey(String paymentKey) {
        return jpaRepository.existsByPaymentKey(paymentKey);
    }

    @Override
    public boolean existsByPaymentRequestId(String paymentRequestId) {
        return jpaRepository.existsByPaymentRequestId(paymentRequestId);
    }

    @Override
    public Optional<Payment> findById(Long paymentId) {
        return jpaRepository.findById(paymentId)
            .map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = PaymentJpaEntity.from(payment);
        PaymentJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public List<Payment> findByUserId(long userId, int page, int size) {
        return jpaRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(page, size))
            .stream().map(PaymentJpaEntity::toDomain).toList();
    }
}
