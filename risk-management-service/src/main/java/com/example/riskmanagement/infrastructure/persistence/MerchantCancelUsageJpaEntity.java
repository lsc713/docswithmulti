package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_cancel_usage",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_merchant_cancel_usage_merchant_id_kst_date",
        columnNames = {"merchant_id", "kst_date"}))
public class MerchantCancelUsageJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "kst_date", nullable = false)
    private LocalDate kstDate;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "used_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal usedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantCancelUsageJpaEntity() {}

    public static MerchantCancelUsageJpaEntity from(MerchantCancelUsage usage) {
        MerchantCancelUsageJpaEntity e = new MerchantCancelUsageJpaEntity();
        e.id = usage.getId();
        if (usage.getId() == null) e.createdAt = Instant.now();
        e.merchantId = usage.getMerchantId();
        e.kstDate = usage.getKstDate();
        e.dailyLimit = usage.getDailyLimit();
        e.usedAmount = usage.getUsedAmount();
        e.updatedAt = Instant.now();
        return e;
    }

    public MerchantCancelUsage toDomain() {
        return MerchantCancelUsage.reconstruct(id, merchantId, kstDate, dailyLimit, usedAmount);
    }

    public Long getId() { return id; }
}
