package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidCancelAmountException;
import com.example.payment.domain.exception.InvalidCancelStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * CancelRequest 도메인 엔티티
 *
 * 상태 전이:
 * PENDING → PROCESSING → COMPLETED (또는 FAILED)
 * FAILED → PENDING (raiseToPending — 재시도)
 * COMPLETED, FAILED는 최종 상태 (단, FAILED는 PENDING 재진입 가능)
 *
 * 멱등성: (payment_id, dedup_key) UK(uk_cancel_request_dedup)로 중복 방어
 * dedup_key = idempotencyKey가 있으면 "ik:"+idempotencyKey, 없으면 "ch:"+request_hash
 * request_hash = SHA-256(paymentKey + paymentItemIds 오름차순 정렬)
 */
public class CancelRequest {

    private Long id;
    private Long paymentId;
    private String requestHash;
    /** 클라 Idempotency-Key 헤더 값(optional) — null이면 request_hash(content-hash)만으로 멱등 판별 */
    private String idempotencyKey;
    private BigDecimal cancelAmount;
    private String cancelReason;
    private List<Long> cancelItemIds;
    private CancelStatus status;
    private int pgRetryCount;
    private Instant completedAt;
    private Instant pgPendingSince;
    private Instant createdAt;
    private Instant updatedAt;
    /** PG(Toss) 취소 transactionKey — 감사 + 부분취소 동일금액 tiebreaker (D-01) */
    private String pgTransactionKey;

    private CancelRequest(Long paymentId, String requestHash,
                          BigDecimal cancelAmount, String cancelReason,
                          List<Long> cancelItemIds, String idempotencyKey) {
        validateCancelAmount(cancelAmount);
        if (cancelItemIds == null || cancelItemIds.isEmpty()) {
            throw new IllegalArgumentException("cancelItemIds must not be null or empty");
        }
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.idempotencyKey = idempotencyKey;
        this.cancelAmount = cancelAmount;
        this.cancelReason = cancelReason;
        this.cancelItemIds = cancelItemIds;
        this.status = CancelStatus.PENDING;
        this.pgRetryCount = 0;
        this.createdAt = Instant.now();
    }

    public static CancelRequest create(Long paymentId, String requestHash,
                                       BigDecimal cancelAmount, String cancelReason,
                                       List<Long> cancelItemIds, String idempotencyKey) {
        return new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason, cancelItemIds, idempotencyKey);
    }

    /** DB에서 조회한 데이터로 재구성 (infrastructure 계층용) */
    public static CancelRequest reconstruct(
        Long id, Long paymentId, String requestHash,
        BigDecimal cancelAmount, String cancelReason,
        List<Long> cancelItemIds, CancelStatus status,
        int pgRetryCount, Instant completedAt,
        Instant pgPendingSince, Instant createdAt, Instant updatedAt,
        String pgTransactionKey, String idempotencyKey
    ) {
        CancelRequest r = new CancelRequest(paymentId, requestHash, cancelAmount, cancelReason, cancelItemIds, idempotencyKey);
        r.id = id;
        r.status = status;
        r.pgRetryCount = pgRetryCount;
        r.completedAt = completedAt;
        r.pgPendingSince = pgPendingSince;
        r.createdAt = createdAt;
        r.updatedAt = updatedAt;
        r.pgTransactionKey = pgTransactionKey;
        return r;
    }

    /** PENDING → PROCESSING */
    public void toProcessing() {
        if (status != CancelStatus.PENDING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PROCESSING);
        }
        this.status = CancelStatus.PROCESSING;
    }

    /** PROCESSING → COMPLETED */
    public void toCompleted() {
        if (status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.COMPLETED);
        }
        this.status = CancelStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** PENDING or PROCESSING → FAILED */
    public void toFailed() {
        if (status != CancelStatus.PENDING && status != CancelStatus.PROCESSING) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.FAILED);
        }
        this.status = CancelStatus.FAILED;
    }

    /** FAILED → PENDING (재시도: UK 유지, 새 INSERT 없음) */
    public void raiseToPending() {
        if (status != CancelStatus.FAILED) {
            throw new InvalidCancelStateTransitionException(status, CancelStatus.PENDING);
        }
        this.status = CancelStatus.PENDING;
        this.pgRetryCount = 0;
    }

    /** pgPendingSince == null일 때만 설정 (멱등) */
    public void markPgPending() {
        if (this.pgPendingSince == null) {
            this.pgPendingSince = Instant.now();
        }
    }

    /** PG 재시도 횟수 1 증가 */
    public void incrementPgRetryCount() {
        this.pgRetryCount++;
    }

    /** PG(Toss) 취소 transactionKey 저장 — cancel()/getStatus() 승인 응답에서 세팅 */
    public void assignPgTransactionKey(String pgTransactionKey) {
        this.pgTransactionKey = pgTransactionKey;
    }

    private void validateCancelAmount(BigDecimal cancelAmount) {
        if (cancelAmount == null || cancelAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidCancelAmountException(cancelAmount);
        }
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getCancelAmount() { return cancelAmount; }
    public String getCancelReason() { return cancelReason; }
    public List<Long> getCancelItemIds() { return cancelItemIds; }
    public CancelStatus getStatus() { return status; }
    public int getPgRetryCount() { return pgRetryCount; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getPgPendingSince() { return pgPendingSince; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getPgTransactionKey() { return pgTransactionKey; }
}
