package com.example.payment.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
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

    /** 주문내역: 본인 결제 최신순 페이지 조회 (P3). */
    List<PaymentJpaEntity> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
