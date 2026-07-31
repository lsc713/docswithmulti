package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Payment Spring Data JPA Repository
 */
public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {

    /**
     * paymentKey로 조회
     */
    Optional<PaymentJpaEntity> findByPaymentKey(String paymentKey);

    /**
     * paymentKey 존재 여부 (경량 exists 조회)
     */
    boolean existsByPaymentKey(String paymentKey);
}
