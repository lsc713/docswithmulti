package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.Payment;
import java.util.Optional;

/**
 * Payment 영속성 인터페이스
 *
 * infrastructure/persistence에서 JPA로 구현
 */
public interface PaymentRepository {

    Optional<Payment> findByPaymentKey(String paymentKey);

    /** paymentKey로 커밋된 Payment 존재 여부 (RST-03 orphan 복구 조회) */
    boolean existsByPaymentKey(String paymentKey);

    /** 복구 스케줄러에서 cancelRequest.getPaymentId()로 Payment 로드 */
    Optional<Payment> findById(Long paymentId);

    Payment save(Payment payment);
}
