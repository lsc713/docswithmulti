package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "stock_release_retry",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_stock_release_payment_key",
        columnNames = "payment_key"
    )
)
public class StockReleaseRetryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false, length = 64)
    private String paymentKey;

    @Column(name = "items_json", nullable = false, length = 1000)
    private String itemsJson;

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

    protected StockReleaseRetryJpaEntity() {}

    public static StockReleaseRetryJpaEntity pending(String paymentKey, String itemsJson) {
        LocalDateTime now = LocalDateTime.now();
        StockReleaseRetryJpaEntity e = new StockReleaseRetryJpaEntity();
        e.paymentKey = paymentKey;
        e.itemsJson = itemsJson;
        e.attemptCount = 0;
        e.nextRetryAt = now; // 즉시 재시도 대상
        e.status = "PENDING";
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public Long getId() { return id; }
    public String getPaymentKey() { return paymentKey; }
    public String getItemsJson() { return itemsJson; }
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

    public void exhaust(int finalAttemptCount, String lastError) {
        this.attemptCount = finalAttemptCount;
        this.status = "FAILED";
        this.lastError = truncate(lastError);
        this.updatedAt = LocalDateTime.now();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
