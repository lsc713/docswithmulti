package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.CancellerType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * CancelRequest JPA 엔티티
 *
 * DDL: V2__create_cancel.sql 기준
 * 주의: Instant를 LocalDateTime으로 매핑
 */
@Entity
@Table(name = "cancel_request",
    indexes = {
        @Index(name = "idx_cancel_request_payment_id", columnList = "payment_id"),
        @Index(name = "idx_cancel_request_status", columnList = "status"),
        @Index(name = "idx_cancel_request_status_created_at", columnList = "status,created_at")
    }
)
public class CancelRequestJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "cancel_amount", nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal cancelAmount;

    @Column(name = "cancel_reason", nullable = true, length = 255)
    private String cancelReason;

    @Column(name = "canceller_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CancellerType cancellerType;

    @Column(name = "cancelled_by", nullable = false)
    private Long cancelledBy;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CancelStatus status;

    @Column(name = "processing_started_at", nullable = true, columnDefinition = "DATETIME(3)")
    private LocalDateTime processingStartedAt;

    @Column(name = "completed_at", nullable = true, columnDefinition = "DATETIME(3)")
    private LocalDateTime completedAt;

    @Column(name = "failed_reason", nullable = true, length = 500)
    private String failedReason;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    protected CancelRequestJpaEntity() {
    }

    private CancelRequestJpaEntity(
        Long paymentId,
        String idempotencyKey,
        BigDecimal cancelAmount,
        String cancelReason,
        CancellerType cancellerType,
        Long cancelledBy,
        CancelStatus status,
        LocalDateTime processingStartedAt,
        LocalDateTime completedAt,
        String failedReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.cancelAmount = cancelAmount;
        this.cancelReason = cancelReason;
        this.cancellerType = cancellerType;
        this.cancelledBy = cancelledBy;
        this.status = status;
        this.processingStartedAt = processingStartedAt;
        this.completedAt = completedAt;
        this.failedReason = failedReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 도메인 객체를 JPA 엔티티로 변환
     * Instant → LocalDateTime 변환
     */
    public static CancelRequestJpaEntity from(CancelRequest request) {
        LocalDateTime createdAtLdt = request.getCreatedAt() != null
            ? LocalDateTime.ofInstant(request.getCreatedAt(), ZoneOffset.UTC)
            : null;
        LocalDateTime updatedAtLdt = request.getUpdatedAt() != null
            ? LocalDateTime.ofInstant(request.getUpdatedAt(), ZoneOffset.UTC)
            : null;
        LocalDateTime processingStartedAtLdt = request.getProcessingStartedAt() != null
            ? LocalDateTime.ofInstant(request.getProcessingStartedAt(), ZoneOffset.UTC)
            : null;
        LocalDateTime completedAtLdt = request.getCompletedAt() != null
            ? LocalDateTime.ofInstant(request.getCompletedAt(), ZoneOffset.UTC)
            : null;

        return new CancelRequestJpaEntity(
            request.getPaymentId(),
            request.getIdempotencyKey(),
            request.getCancelAmount(),
            request.getCancelReason(),
            request.getCancellerType(),
            request.getCancelledBy(),
            request.getStatus(),
            processingStartedAtLdt,
            completedAtLdt,
            request.getFailedReason(),
            createdAtLdt,
            updatedAtLdt
        );
    }

    /**
     * JPA 엔티티를 도메인 객체로 변환
     * LocalDateTime → Instant 변환
     */
    public CancelRequest toDomain() {
        Instant createdAtInst = createdAt != null
            ? createdAt.toInstant(ZoneOffset.UTC)
            : null;
        Instant updatedAtInst = updatedAt != null
            ? updatedAt.toInstant(ZoneOffset.UTC)
            : null;
        Instant processingStartedAtInst = processingStartedAt != null
            ? processingStartedAt.toInstant(ZoneOffset.UTC)
            : null;
        Instant completedAtInst = completedAt != null
            ? completedAt.toInstant(ZoneOffset.UTC)
            : null;

        return CancelRequest.reconstruct(
            id,
            paymentId,
            idempotencyKey,
            cancelAmount,
            cancelReason,
            cancellerType,
            cancelledBy,
            status,
            processingStartedAtInst,
            completedAtInst,
            failedReason,
            createdAtInst,
            updatedAtInst
        );
    }

    // ===== Getters & Setters =====

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getCancelAmount() {
        return cancelAmount;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public CancellerType getCancellerType() {
        return cancellerType;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public CancelStatus getStatus() {
        return status;
    }

    public void setStatus(CancelStatus status) {
        this.status = status;
    }

    public LocalDateTime getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(LocalDateTime processingStartedAt) {
        this.processingStartedAt = processingStartedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getFailedReason() {
        return failedReason;
    }

    public void setFailedReason(String failedReason) {
        this.failedReason = failedReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
