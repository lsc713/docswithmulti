package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.Payment;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * Payment 영속성 인터페이스
 *
 * infrastructure/persistence에서 JPA로 구현
 */
public interface PaymentRepository {

    Optional<Payment> findByPaymentKey(String paymentKey);

    Optional<Payment> findByPaymentRequestId(String paymentRequestId);

    Optional<Payment> findByPaymentRequestIdForUpdate(String paymentRequestId);

    List<Payment> findPendingRecoveryCandidates(
        LocalDateTime expiresAt, LocalDateTime unknownAt, int size);

    /** paymentKey로 커밋된 Payment 존재 여부 (RST-03 orphan 복구 조회) */
    boolean existsByPaymentKey(String paymentKey);

    boolean existsByPaymentRequestId(String paymentRequestId);

    /** 복구 스케줄러에서 cancelRequest.getPaymentId()로 Payment 로드 */
    Optional<Payment> findById(Long paymentId);

    Payment save(Payment payment);

    /** 주문내역: 본인 결제 최신순 페이지 조회 (P3). */
    List<Payment> findByUserId(long userId, int page, int size);
}
