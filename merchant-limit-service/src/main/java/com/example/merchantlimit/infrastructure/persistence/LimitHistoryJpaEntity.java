package com.example.merchantlimit.infrastructure.persistence;

import com.example.merchantlimit.domain.entity.LimitHistory;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "merchant_cancel_limit_history",
    indexes = @Index(name = "idx_limit_history_merchant_id", columnList = "merchant_id"))
public class LimitHistoryJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "old_limit", precision = 19, scale = 2)
    private BigDecimal oldLimit;

    @Column(name = "new_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal newLimit;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LimitHistoryJpaEntity() {}

    public static LimitHistoryJpaEntity from(LimitHistory h) {
        LimitHistoryJpaEntity e = new LimitHistoryJpaEntity();
        e.merchantId = h.getMerchantId();
        e.oldLimit = h.getOldLimit();
        e.newLimit = h.getNewLimit();
        e.reason = h.getReason();
        e.createdAt = h.getCreatedAt();
        return e;
    }

    public LimitHistory toDomain() {
        return LimitHistory.record(merchantId, oldLimit, newLimit, reason);
    }

    public Long getId()             { return id; }
    public Long getMerchantId()     { return merchantId; }
    public BigDecimal getOldLimit() { return oldLimit; }
    public BigDecimal getNewLimit() { return newLimit; }
    public String getReason()       { return reason; }
    public Instant getCreatedAt()   { return createdAt; }
}
