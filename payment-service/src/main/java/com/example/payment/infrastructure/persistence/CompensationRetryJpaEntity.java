package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

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

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompensationRetryJpaEntity() {}

    public static CompensationRetryJpaEntity pending(
        long cancelRequestId, long merchantId, BigDecimal restoreAmount
    ) {
        CompensationRetryJpaEntity e = new CompensationRetryJpaEntity();
        e.cancelRequestId = String.valueOf(cancelRequestId);
        e.merchantId = merchantId;
        e.restoreAmount = restoreAmount;
        e.attemptCount = 0;
        e.nextRetryAt = Instant.now().plusSeconds(60); // 1분 후 첫 재시도
        e.status = "PENDING";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    public Long getId() { return id; }
    public long getCancelRequestIdAsLong() { return Long.parseLong(cancelRequestId); }
    public Long getMerchantId() { return merchantId; }
    public BigDecimal getRestoreAmount() { return restoreAmount; }
    public int getAttemptCount() { return attemptCount; }

    public void markDone() {
        this.status = "DONE";
        this.updatedAt = Instant.now();
    }

    public void markFailed(int newAttemptCount, Instant nextRetryAt, String lastError) {
        this.attemptCount = newAttemptCount;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError != null && lastError.length() > 500
            ? lastError.substring(0, 500) : lastError;
        this.status = "PENDING"; // 최대 시도 초과 시 호출자가 FAILED로 설정
        this.updatedAt = Instant.now();
    }

    public void exhaust(String lastError) {
        this.status = "FAILED";
        this.lastError = lastError != null && lastError.length() > 500
            ? lastError.substring(0, 500) : lastError;
        this.updatedAt = Instant.now();
    }
}
