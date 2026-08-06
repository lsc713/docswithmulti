package com.example.settlement.infrastructure.persistence;

import com.example.settlement.domain.entity.Reserve;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * reserve JPA 매핑. 컬럼명/타입은 V3 DDL과 정확히 일치해야 함(ddl-auto=validate) —
 * amount DECIMAL(19,2), status VARCHAR(20), hold_until DATE→LocalDate, transfer_ref VARCHAR(120).
 * INSERT 는 native(insertHeld) — 이 엔티티는 조회 매핑 중심(PayoutJpaEntity 미러).
 */
@Entity
@Table(name = "reserve")
public class ReserveJpaEntity {

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

    @Column(name = "hold_until", nullable = false)
    private LocalDate holdUntil;

    @Column(name = "transfer_ref", nullable = false, length = 120)
    private String transferRef;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "held_at", nullable = false)
    private Instant heldAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReserveJpaEntity() {}

    public Reserve toDomain() {
        return Reserve.reconstruct(id, settlementId, merchantId, amount, status, holdUntil, transferRef,
            attemptCount, lastError, heldAt, releasedAt, createdAt, updatedAt);
    }
}
