package com.example.settlement.infrastructure.persistence;

import com.example.settlement.domain.entity.Payout;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * payout JPA 매핑. 컬럼명/타입은 V2 DDL과 정확히 일치해야 함(ddl-auto=validate) —
 * amount DECIMAL(19,2), status VARCHAR(20), transfer_ref VARCHAR(120) NOT NULL, last_error VARCHAR(500) NULL.
 * INSERT 는 save(newProcessing), 확정은 status-guarded native UPDATE(applyResult) — 이 엔티티는 조회 매핑 중심.
 */
@Entity
@Table(name = "payout")
public class PayoutJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "transfer_ref", nullable = false, length = 120)
    private String transferRef;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayoutJpaEntity() {}

    /** 승인 시점 PROCESSING 신규 건 생성(단일 INSERT). transfer_ref 는 승인부에서 결정('PO-'+settlementId). */
    static PayoutJpaEntity newProcessing(long settlementId, long merchantId, BigDecimal amount,
                                         String transferRef, Instant now) {
        PayoutJpaEntity e = new PayoutJpaEntity();
        e.settlementId = settlementId;
        e.merchantId = merchantId;
        e.amount = amount;
        e.status = "PROCESSING";
        e.transferRef = transferRef;
        e.attemptCount = 1;
        e.lastError = null;
        e.requestedAt = now;
        e.paidAt = null;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public Payout toDomain() {
        return Payout.reconstruct(id, settlementId, merchantId, amount, status, transferRef,
            attemptCount, lastError, requestedAt, paidAt, createdAt, updatedAt);
    }
}
