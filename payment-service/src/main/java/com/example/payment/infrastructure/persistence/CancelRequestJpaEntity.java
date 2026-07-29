package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.infrastructure.persistence.converter.LongListConverter;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * CancelRequest JPA 엔티티
 *
 * DDL: V2__create_cancel.sql + V8__align_cancel_schema.sql 기준
 * V8에서 idempotency_key → request_hash UK로 변경
 */
@Entity
@Table(name = "cancel_request",
    indexes = {
        @Index(name = "idx_cancel_request_payment_id", columnList = "payment_id"),
        @Index(name = "idx_cancel_request_status", columnList = "status"),
        @Index(name = "idx_cancel_request_status_created_at", columnList = "status,created_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_cancel_request_hash", columnNames = {"payment_id", "request_hash"})
    }
)
public class CancelRequestJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "cancel_amount", nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal cancelAmount;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancel_item_ids", nullable = false, columnDefinition = "JSON")
    @Convert(converter = LongListConverter.class)
    private List<Long> cancelItemIds;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CancelStatus status;

    @Column(name = "pg_retry_count", nullable = false)
    private int pgRetryCount;

    @Column(name = "pg_pending_since", columnDefinition = "DATETIME(3)")
    private LocalDateTime pgPendingSince;

    @Column(name = "completed_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    @Column(name = "pg_transaction_key", length = 64)
    private String pgTransactionKey;

    protected CancelRequestJpaEntity() {}

    public static CancelRequestJpaEntity from(CancelRequest request) {
        CancelRequestJpaEntity e = new CancelRequestJpaEntity();
        e.id = request.getId();
        e.paymentId = request.getPaymentId();
        e.requestHash = request.getRequestHash();
        e.cancelAmount = request.getCancelAmount();
        e.cancelReason = request.getCancelReason();
        e.cancelItemIds = request.getCancelItemIds();
        e.status = request.getStatus();
        e.pgRetryCount = request.getPgRetryCount();
        e.pgPendingSince = toLocalDateTime(request.getPgPendingSince());
        e.completedAt = toLocalDateTime(request.getCompletedAt());
        e.createdAt = toLocalDateTime(request.getCreatedAt());
        e.updatedAt = request.getUpdatedAt() != null
            ? toLocalDateTime(request.getUpdatedAt())
            : LocalDateTime.now(ZoneOffset.UTC);
        e.pgTransactionKey = request.getPgTransactionKey();
        return e;
    }

    public CancelRequest toDomain() {
        return CancelRequest.reconstruct(
            id, paymentId, requestHash,
            cancelAmount, cancelReason, cancelItemIds,
            status, pgRetryCount,
            toInstant(completedAt),
            toInstant(pgPendingSince),
            toInstant(createdAt),
            toInstant(updatedAt),
            pgTransactionKey
        );
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public BigDecimal getCancelAmount() { return cancelAmount; }
    public List<Long> getCancelItemIds() { return cancelItemIds; }
    public String getCancelReason() { return cancelReason; }
    public CancelStatus getStatus() { return status; }
    public void setStatus(CancelStatus status) { this.status = status; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getPgTransactionKey() { return pgTransactionKey; }
}
