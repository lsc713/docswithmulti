package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * PaymentItem Spring Data JPA Repository
 */
public interface PaymentItemJpaRepository extends JpaRepository<PaymentItemJpaEntity, Long> {

    /**
     * paymentId로 모든 항목 조회
     */
    List<PaymentItemJpaEntity> findAllByPaymentId(Long paymentId);
}
