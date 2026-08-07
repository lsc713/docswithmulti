package com.example.payment.domain.entity;

import java.time.Instant;

public class CancelApproval {
    private Long id;
    private long paymentId;
    private String paymentKey;
    private long requesterUserId;
    private String reason;
    private CancelApprovalStatus status;
    private Long decidedByUserId;
    private String decidedRole;
    private String decisionReason;
    private Long cancelRequestId;
    private Instant createdAt;
    private Instant updatedAt;

    protected CancelApproval() {}

    public static CancelApproval request(long paymentId, String paymentKey, long requesterUserId, String reason) {
        CancelApproval a = new CancelApproval();
        a.paymentId = paymentId;
        a.paymentKey = paymentKey;
        a.requesterUserId = requesterUserId;
        a.reason = reason;
        a.status = CancelApprovalStatus.REQUESTED;
        return a;
    }

    /** 영속 로드용 재구성 (RepositoryImpl에서 사용) */
    public static CancelApproval reconstitute(Long id, long paymentId, String paymentKey, long requesterUserId,
            String reason, CancelApprovalStatus status, Long decidedByUserId, String decidedRole,
            String decisionReason, Long cancelRequestId, Instant createdAt, Instant updatedAt) {
        CancelApproval a = new CancelApproval();
        a.id = id; a.paymentId = paymentId; a.paymentKey = paymentKey; a.requesterUserId = requesterUserId;
        a.reason = reason; a.status = status; a.decidedByUserId = decidedByUserId; a.decidedRole = decidedRole;
        a.decisionReason = decisionReason; a.cancelRequestId = cancelRequestId;
        a.createdAt = createdAt; a.updatedAt = updatedAt;
        return a;
    }

    public void approve(long deciderUserId, String deciderRole, long cancelRequestId) {
        requireRequested();
        this.status = CancelApprovalStatus.APPROVED;
        this.decidedByUserId = deciderUserId;
        this.decidedRole = deciderRole;
        this.cancelRequestId = cancelRequestId;
    }

    public void reject(long deciderUserId, String deciderRole, String decisionReason) {
        requireRequested();
        this.status = CancelApprovalStatus.REJECTED;
        this.decidedByUserId = deciderUserId;
        this.decidedRole = deciderRole;
        this.decisionReason = decisionReason;
    }

    private void requireRequested() {
        if (status != CancelApprovalStatus.REQUESTED) {
            throw new IllegalStateException("이미 결정된 승인 요청입니다: " + status);
        }
    }

    public Long getId() { return id; }
    public long getPaymentId() { return paymentId; }
    public String getPaymentKey() { return paymentKey; }
    public long getRequesterUserId() { return requesterUserId; }
    public String getReason() { return reason; }
    public CancelApprovalStatus getStatus() { return status; }
    public Long getDecidedByUserId() { return decidedByUserId; }
    public String getDecidedRole() { return decidedRole; }
    public String getDecisionReason() { return decisionReason; }
    public Long getCancelRequestId() { return cancelRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
