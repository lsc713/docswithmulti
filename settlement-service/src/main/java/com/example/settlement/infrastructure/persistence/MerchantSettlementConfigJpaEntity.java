package com.example.settlement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * merchant_settlement_config(요율 원본) JPA 매핑. 쓰기는 native upsert로 처리하므로 read-mostly.
 * 컬럼명/타입은 V1 DDL과 정확히 일치해야 함(ddl-auto=validate) —
 * merchant_id PK(client 지정, @GeneratedValue 없음), fee_rate DECIMAL(5,4), active, created_at/updated_at DATETIME(3).
 */
@Entity
@Table(name = "merchant_settlement_config")
public class MerchantSettlementConfigJpaEntity {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantSettlementConfigJpaEntity() {}

    public BigDecimal getFeeRate() { return feeRate; }
}
