package com.example.settlement.infrastructure.persistence;

import com.example.settlement.domain.entity.MerchantReserveConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * merchant_reserve_config(유보 정책 원본) JPA 매핑. 쓰기는 native upsert라 read-mostly.
 * 컬럼명/타입은 V3 DDL과 정확히 일치해야 함(ddl-auto=validate) —
 * merchant_id PK(client 지정, @GeneratedValue 없음), reserve_rate DECIMAL(5,4), reserve_cap DECIMAL(19,2),
 * hold_days INT, active, created_at/updated_at DATETIME(3). MerchantSettlementConfigJpaEntity 미러.
 */
@Entity
@Table(name = "merchant_reserve_config")
public class MerchantReserveConfigJpaEntity {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "reserve_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal reserveRate;

    @Column(name = "reserve_cap", nullable = false, precision = 19, scale = 2)
    private BigDecimal reserveCap;

    @Column(name = "hold_days", nullable = false)
    private Integer holdDays;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantReserveConfigJpaEntity() {}

    public MerchantReserveConfig toDomain() {
        return MerchantReserveConfig.reconstruct(merchantId, reserveRate, reserveCap, holdDays);
    }
}
