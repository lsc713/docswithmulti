package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cancel_usage_compensation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cancel_usage_compensation_cancel_request_id",
        columnNames = "cancel_request_id"))
public class CancelUsageCompensationJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64)
    private String cancelRequestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "restore_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal restoreAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CancelUsageCompensationJpaEntity() {}

    public static CancelUsageCompensationJpaEntity from(CancelUsageCompensation compensation) {
        CancelUsageCompensationJpaEntity e = new CancelUsageCompensationJpaEntity();
        e.cancelRequestId = compensation.getCancelRequestId();
        e.merchantId = compensation.getMerchantId();
        e.restoreAmount = compensation.getRestoreAmount();
        e.createdAt = Instant.now();
        return e;
    }

    public CancelUsageCompensation toDomain() {
        return CancelUsageCompensation.reconstruct(id, cancelRequestId, merchantId, restoreAmount);
    }
}
