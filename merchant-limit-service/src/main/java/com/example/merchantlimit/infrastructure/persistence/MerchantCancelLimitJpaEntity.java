package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "merchant_cancel_limit",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_merchant_cancel_limit_merchant_id",
        columnNames = "merchant_id"))
public class MerchantCancelLimitJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantCancelLimitJpaEntity() {}

    public static MerchantCancelLimitJpaEntity from(MerchantCancelLimit limit) {
        MerchantCancelLimitJpaEntity e = new MerchantCancelLimitJpaEntity();
        e.id = limit.getId();
        e.merchantId = limit.getMerchantId();
        e.dailyLimit = limit.getDailyLimit();
        e.updatedAt = Instant.now();
        return e;
    }

    public MerchantCancelLimit toDomain() {
        return MerchantCancelLimit.reconstruct(id, merchantId, dailyLimit);
    }

    public Long getId() { return id; }
}
