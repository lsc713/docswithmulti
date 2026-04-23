package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cancel_usage_history",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cancel_usage_history_cancel_request_id",
        columnNames = "cancel_request_id"))
public class CancelUsageHistoryJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64)
    private String cancelRequestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "kst_date", nullable = false)
    private LocalDate kstDate;

    @Column(name = "cancel_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cancelAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CancelUsageHistoryJpaEntity() {}

    public static CancelUsageHistoryJpaEntity from(CancelUsageHistory history) {
        CancelUsageHistoryJpaEntity e = new CancelUsageHistoryJpaEntity();
        e.cancelRequestId = history.getCancelRequestId();
        e.merchantId = history.getMerchantId();
        e.kstDate = history.getKstDate();
        e.cancelAmount = history.getCancelAmount();
        e.createdAt = Instant.now();
        return e;
    }

    public CancelUsageHistory toDomain() {
        return CancelUsageHistory.reconstruct(id, cancelRequestId, merchantId, kstDate, cancelAmount);
    }
}
