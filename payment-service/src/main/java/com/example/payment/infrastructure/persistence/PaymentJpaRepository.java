package com.example.payment.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * Payment Spring Data JPA Repository
 */
public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {

    /**
     * paymentKey로 조회
     */
    Optional<PaymentJpaEntity> findByPaymentKey(String paymentKey);

    Optional<PaymentJpaEntity> findByPaymentRequestId(String paymentRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.paymentRequestId = :paymentRequestId")
    Optional<PaymentJpaEntity> findByPaymentRequestIdForUpdate(
        @Param("paymentRequestId") String paymentRequestId);

    @Query("SELECT p FROM PaymentJpaEntity p WHERE p.status = com.example.payment.domain.entity.PaymentStatus.PENDING "
        + "AND ((p.paymentKey IS NULL AND p.createdAt <= :expiresAt) "
        + "OR (p.paymentKey IS NOT NULL AND p.updatedAt <= :unknownAt)) ORDER BY p.id")
    List<PaymentJpaEntity> findPendingRecoveryCandidates(
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("unknownAt") LocalDateTime unknownAt,
        Pageable pageable);

    /**
     * paymentKey 존재 여부 (경량 exists 조회)
     */
    boolean existsByPaymentKey(String paymentKey);

    boolean existsByPaymentRequestId(String paymentRequestId);

    /** 주문내역: 본인 결제 최신순 페이지 조회 (P3). */
    List<PaymentJpaEntity> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
