package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * RECON-03 정산 대사 read-only repository (NEW). 기존 payment/cancel_request 컬럼만 조회 —
 * 마이그레이션 없음, write 없음. PaymentJpaRepository에 메서드를 추가하지 않고 이 신규 파일에
 * 격리해 INV-01 allowlist(PaymentSettlement 단일 토큰)를 유지한다.
 */
public interface PaymentSettlementJpaRepository extends JpaRepository<PaymentJpaEntity, Long> {

    /**
     * SALE 윈도우: payment.created_at ∈ [from,to) 인 가맹점 결제 (전 상태 — PENDING은 서비스에서 제외).
     * idx_payment_merchant_id가 가맹점 스캔을 커버 (RESEARCH A1 주간배치 허용).
     */
    List<PaymentJpaEntity> findByMerchantIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
        Long merchantId, LocalDateTime from, LocalDateTime to);

    /**
     * CANCEL 윈도우: cancel_request.completed_at ∈ [from,to) 인 COMPLETED 취소를 부모 payment와 조인.
     * 부모 결제 필드까지 실어 부모가 윈도우 밖(carrier)이어도 응답을 조립할 수 있게 한다.
     */
    @Query(value = """
        SELECT cr.id           AS cancelRequestId,
               cr.cancel_amount AS cancelAmount,
               cr.completed_at  AS completedAt,
               p.payment_key    AS paymentKey,
               p.merchant_id    AS merchantId,
               p.total_amount   AS totalAmount,
               p.status         AS status,
               p.created_at     AS createdAt
        FROM cancel_request cr
        JOIN payment p ON cr.payment_id = p.id
        WHERE p.merchant_id = :merchantId
          AND cr.status = 'COMPLETED'
          AND cr.completed_at >= :from
          AND cr.completed_at < :to
        """, nativeQuery = true)
    List<CancelRow> findCompletedCancelsInWindow(
        @Param("merchantId") long merchantId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);

    /** CANCEL 윈도우 native projection — 취소 + 부모 결제 스냅샷. */
    interface CancelRow {
        Long getCancelRequestId();
        BigDecimal getCancelAmount();
        LocalDateTime getCompletedAt();
        String getPaymentKey();
        Long getMerchantId();
        BigDecimal getTotalAmount();
        String getStatus();
        LocalDateTime getCreatedAt();
    }
}
