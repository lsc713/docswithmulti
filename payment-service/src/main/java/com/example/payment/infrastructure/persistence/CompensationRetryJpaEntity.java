package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "compensation_retry",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_compensation_cancel_request_id",
        columnNames = "cancel_request_id"
    )
)
public class CompensationRetryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64)
    private String cancelRequestId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "restore_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal restoreAmount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime nextRetryAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    protected CompensationRetryJpaEntity() {}

    public static CompensationRetryJpaEntity pending(
        long cancelRequestId, long merchantId, BigDecimal restoreAmount
    ) {
        LocalDateTime now = LocalDateTime.now();
        CompensationRetryJpaEntity e = new CompensationRetryJpaEntity();
        e.cancelRequestId = String.valueOf(cancelRequestId);
        e.merchantId = merchantId;
        e.restoreAmount = restoreAmount;
        e.attemptCount = 0;
        e.nextRetryAt = now.plusSeconds(60); // 1분 후 첫 재시도
        e.status = "PENDING";
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public Long getId() { return id; }
    public long getCancelRequestIdAsLong() { return Long.parseLong(cancelRequestId); }
    public Long getMerchantId() { return merchantId; }
    public BigDecimal getRestoreAmount() { return restoreAmount; }
    public int getAttemptCount() { return attemptCount; }

    public void markDone() {
        this.status = "DONE";
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(int newAttemptCount, LocalDateTime nextRetryAt, String lastError) {
        this.attemptCount = newAttemptCount;
        this.nextRetryAt = nextRetryAt;
        this.lastError = truncate(lastError);
        this.status = "PENDING";
        this.updatedAt = LocalDateTime.now();
    }

    public void exhaust(String lastError) {
        this.status = "FAILED";
        this.lastError = truncate(lastError);
        this.updatedAt = LocalDateTime.now();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
