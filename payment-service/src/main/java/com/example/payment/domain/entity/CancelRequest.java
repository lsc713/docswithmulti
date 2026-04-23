package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CancelRequest 도메인 엔티티
 *
 * 상태 전이:
 * PENDING → PROCESSING → COMPLETED (또는 FAILED)
 * COMPLETED, FAILED는 최종 상태 (변경 불가)
 *
 * 비즈니스 규칙:
 * - cancelAmount는 1원 이상이어야 한다 (domain-rules.md 2-3)
 * - 도메인 계층에서 외부 시스템 호출 금지
 */
public class CancelRequest {

    private Long id;
    private Long paymentId;
    private String idempotencyKey;
    private BigDecimal cancelAmount;
    private String cancelReason;
    private CancellerType cancellerType;
    private Long cancelledBy;
    private CancelStatus status;
    private Instant processingStartedAt;
    private Instant completedAt;
    private String failedReason;
    private Instant createdAt;
    private Instant updatedAt;

    private CancelRequest(
        Long paymentId,
        String idempotencyKey,
        BigDecimal cancelAmount,
        String cancelReason,
        CancellerType cancellerType,
        Long cancelledBy
    ) {
        validateCancelAmount(cancelAmount);

        this.paymentId = paymentId;
        this.idempotencyKey = idempotencyKey;
        this.cancelAmount = cancelAmount;
        this.cancelReason = cancelReason;
        this.cancellerType = cancellerType;
        this.cancelledBy = cancelledBy;
        this.status = CancelStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static CancelRequest create(
        Long paymentId,
        String idempotencyKey,
        BigDecimal cancelAmount,
        String cancelReason,
        CancellerType cancellerType,
        Long cancelledBy
    ) {
        return new CancelRequest(
            paymentId,
            idempotencyKey,
            cancelAmount,
            cancelReason,
            cancellerType,
            cancelledBy
        );
    }

    /**
     * DB에서 조회한 데이터로 CancelRequest를 재구성한다 (infrastructure 계층용)
     */
    public static CancelRequest reconstruct(
        Long id,
        Long paymentId,
        String idempotencyKey,
        BigDecimal cancelAmount,
        String cancelReason,
        CancellerType cancellerType,
        Long cancelledBy,
        CancelStatus status,
        Instant processingStartedAt,
        Instant completedAt,
        String failedReason,
        Instant createdAt,
        Instant updatedAt
    ) {
        CancelRequest request = new CancelRequest(
            paymentId,
            idempotencyKey,
            cancelAmount,
            cancelReason,
            cancellerType,
            cancelledBy
        );
        // DB에서 재구성하므로 현재 상태 직접 설정
        request.id = id;
        request.status = status;
        request.processingStartedAt = processingStartedAt;
        request.completedAt = completedAt;
        request.failedReason = failedReason;
        request.createdAt = createdAt;
        request.updatedAt = updatedAt;
        return request;
    }

    public void toProcessing() {
        if (status != CancelStatus.PENDING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PROCESSING);
        }
        this.status = CancelStatus.PROCESSING;
        this.processingStartedAt = Instant.now();
    }

    public void toCompleted() {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.COMPLETED);
        }
        this.status = CancelStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void toFailed(String failedReason) {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.FAILED);
        }
        this.status = CancelStatus.FAILED;
        this.failedReason = failedReason;
    }

    private void validateCancelAmount(BigDecimal cancelAmount) {
        if (cancelAmount == null || cancelAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidCancelAmountException(cancelAmount);
        }
    }

    // Getters (Setter는 도메인 규칙에 의해 상태 전이 메서드로만 변경)
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

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getFailedReason() {
        return failedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
