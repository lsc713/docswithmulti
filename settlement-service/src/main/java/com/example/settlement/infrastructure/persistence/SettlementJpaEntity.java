package com.example.settlement.infrastructure.persistence;

import com.example.settlement.domain.entity.Settlement;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * settlement 헤더 JPA 매핑. 적재는 native upsert/increment로 처리하므로 read-mostly.
 * 컬럼명/타입은 V1 DDL과 정확히 일치해야 함(ddl-auto=validate).
 */
@Entity
@Table(name = "settlement",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_settlement_merchant_period",
        columnNames = {"merchant_id", "period_start"}))
public class SettlementJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "cancel_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cancelAmount;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettlementJpaEntity() {}

    public Settlement toDomain() {
        return Settlement.reconstruct(id, merchantId, periodStart, periodEnd,
            grossAmount, cancelAmount, feeAmount, vatAmount, netAmount,
            status, finalizedAt, createdAt, updatedAt);
    }

    public Long getId() { return id; }
}
