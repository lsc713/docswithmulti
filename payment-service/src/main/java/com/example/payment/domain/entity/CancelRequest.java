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
 * FAILED → PENDING (raiseToPending — 재시도)
 * COMPLETED, FAILED는 최종 상태 (단, FAILED는 PENDING 재진입 가능)
 *
 * 멱등성: (payment_id, request_hash) UK로 중복 방어
 * request_hash = SHA-256(paymentKey + paymentItemIds 오름차순 정렬)
 */
public class CancelRequest {

    private Long id;
    private Long paymentId;
    private String requestHash;
    private BigDecimal cancelAmount;
    private String cancelReason;
    private CancelStatus status;
    private Instant processingStartedAt;
    private Instant completedAt;
    private String failedReason;
    private Instant pgPendingSince;
    private Instant createdAt;
    private Instant updatedAt;

    private CancelRequest(Long paymentId, String requestHash,
                          BigDecimal cancelAmount, String cancelReason) {
        validateCancelAmount(cancelAmount);
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.cancelAmount = cancelAmount;
        this.cancelReason = cancelReason;
        this.status = CancelStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static CancelRequest create(Long paymentId, String requestHash,
                                       BigDecimal cancelAmount, String cancelReason) {
        return new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason);
    }

    /** DB에서 조회한 데이터로 재구성 (infrastructure 계층용) */
    public static CancelRequest reconstruct(
        Long id, Long paymentId, String requestHash,
        BigDecimal cancelAmount, String cancelReason,
        CancelStatus status, Instant processingStartedAt,
        Instant completedAt, String failedReason,
        Instant pgPendingSince, Instant createdAt, Instant updatedAt
    ) {
        CancelRequest r = new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason);
        r.id = id;
        r.status = status;
        r.processingStartedAt = processingStartedAt;
        r.completedAt = completedAt;
        r.failedReason = failedReason;
        r.pgPendingSince = pgPendingSince;
        r.createdAt = createdAt;
        r.updatedAt = updatedAt;
        return r;
    }

    /** PENDING → PROCESSING */
    public void toProcessing() {
        if (status != CancelStatus.PENDING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PROCESSING);
        }
        this.status = CancelStatus.PROCESSING;
        this.processingStartedAt = Instant.now();
    }

    /** PROCESSING → COMPLETED */
    public void toCompleted() {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.COMPLETED);
        }
        this.status = CancelStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** PROCESSING → FAILED */
    public void toFailed(String reason) {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.FAILED);
        }
        this.status = CancelStatus.FAILED;
        this.failedReason = reason;
    }

    /** FAILED → PENDING (재시도: UK 유지, 새 INSERT 없음) */
    public void raiseToPending() {
        if (status != CancelStatus.FAILED) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PENDING);
        }
        this.status = CancelStatus.PENDING;
        this.failedReason = null;
        this.processingStartedAt = null;
    }

    private void validateCancelAmount(BigDecimal cancelAmount) {
        if (cancelAmount == null || cancelAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidCancelAmountException(cancelAmount);
        }
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public BigDecimal getCancelAmount() { return cancelAmount; }
    public String getCancelReason() { return cancelReason; }
    public CancelStatus getStatus() { return status; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getFailedReason() { return failedReason; }
    public Instant getPgPendingSince() { return pgPendingSince; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
