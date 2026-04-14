package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.Payment;

import java.util.Optional;

/**
 * Payment 영속성 인터페이스
 *
 * infrastructure/persistence에서 JPA로 구현
 */
public interface PaymentRepository {

    /**
     * paymentKey로 Payment 조회
     */
    Optional<Payment> findByPaymentKey(String paymentKey);

    /**
     * Payment 저장 또는 업데이트
     */
    Payment save(Payment payment);
}
