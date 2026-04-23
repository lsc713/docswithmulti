package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant",
    uniqueConstraints = @UniqueConstraint(name = "uk_merchant_key", columnNames = "merchant_key"))
public class MerchantJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_key", nullable = false, length = 64)
    private String merchantKey;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "cancel_period_days", nullable = false)
    private int cancelPeriodDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantJpaEntity() {}

    public static MerchantJpaEntity from(Merchant m) {
        MerchantJpaEntity e = new MerchantJpaEntity();
        e.id = m.getId();
        e.merchantKey = m.getMerchantKey();
        e.name = m.getName();
        e.status = m.getStatus().name();
        e.cancelPeriodDays = m.getCancelPeriodDays();
        if (m.getId() == null) {
            e.createdAt = Instant.now();
        }
        e.updatedAt = Instant.now();
        return e;
    }

    public Merchant toDomain() {
        return Merchant.reconstruct(id, merchantKey, name,
            MerchantStatus.valueOf(status), cancelPeriodDays);
    }

    public Long getId() { return id; }
    public String getMerchantKey() { return merchantKey; }
    public void setStatus(String status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
