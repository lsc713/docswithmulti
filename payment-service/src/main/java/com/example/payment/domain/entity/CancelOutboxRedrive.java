package com.example.payment.domain.entity;

import java.time.Instant;

public class CancelOutboxRedrive {
    private Long id;
    private long sourceOutboxId;
    private CancelOutboxRedriveStatus status;
    private CancelOutboxRedriveFailureStage failureStage;
    private String requestedBy;
    private String reason;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant completedAt;
    private String result;
    private String lastError;
    private String beforeState;
    private String afterState;

    protected CancelOutboxRedrive() {}

    public static CancelOutboxRedrive requested(long sourceOutboxId, String requestedBy, String reason,
                                                Instant requestedAt) {
        CancelOutboxRedrive redrive = new CancelOutboxRedrive();
        redrive.sourceOutboxId = sourceOutboxId;
        redrive.status = CancelOutboxRedriveStatus.REQUESTED;
        redrive.requestedBy = requestedBy;
        redrive.reason = reason;
        redrive.requestedAt = requestedAt;
        return redrive;
    }

    public static CancelOutboxRedrive reconstitute(
        Long id, long sourceOutboxId, CancelOutboxRedriveStatus status,
        CancelOutboxRedriveFailureStage failureStage, String requestedBy, String reason,
        Instant requestedAt, Instant startedAt, Instant completedAt, String result, String lastError,
        String beforeState, String afterState
    ) {
        CancelOutboxRedrive redrive = new CancelOutboxRedrive();
        redrive.id = id;
        redrive.sourceOutboxId = sourceOutboxId;
        redrive.status = status;
        redrive.failureStage = failureStage;
        redrive.requestedBy = requestedBy;
        redrive.reason = reason;
        redrive.requestedAt = requestedAt;
        redrive.startedAt = startedAt;
        redrive.completedAt = completedAt;
        redrive.result = result;
        redrive.lastError = lastError;
        redrive.beforeState = beforeState;
        redrive.afterState = afterState;
        return redrive;
    }

    public void start(Instant startedAt) {
        requireStatus(CancelOutboxRedriveStatus.REQUESTED);
        this.status = CancelOutboxRedriveStatus.REDRIVING;
        this.failureStage = null;
        this.startedAt = startedAt;
    }

    public void resolve(String result, String afterState, Instant completedAt) {
        requireRedriving();
        this.status = CancelOutboxRedriveStatus.RESOLVED;
        this.failureStage = null;
        this.result = result;
        this.afterState = afterState;
        this.completedAt = completedAt;
    }

    public void resolveAlreadyApplied(String result, String afterState, Instant completedAt) {
        requireRedriving();
        this.status = CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED;
        this.failureStage = null;
        this.result = result;
        this.afterState = afterState;
        this.completedAt = completedAt;
    }

    public void reject(String lastError, String beforeState, String afterState, Instant completedAt) {
        requireRedriving();
        this.status = CancelOutboxRedriveStatus.REJECTED;
        this.failureStage = null;
        this.lastError = lastError;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.completedAt = completedAt;
    }

    public void fail(CancelOutboxRedriveFailureStage failureStage, String lastError, String beforeState,
                     Instant completedAt) {
        requireRedriving();
        if (failureStage == null) {
            throw new IllegalArgumentException("failureStage is required for FAILED redrives");
        }
        this.status = CancelOutboxRedriveStatus.FAILED;
        this.failureStage = failureStage;
        this.lastError = lastError;
        this.beforeState = beforeState;
        this.completedAt = completedAt;
    }

    private void requireRedriving() {
        requireStatus(CancelOutboxRedriveStatus.REDRIVING);
    }

    private void requireStatus(CancelOutboxRedriveStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Invalid cancel outbox redrive status: " + status);
        }
    }

    public Long getId() { return id; }
    public long getSourceOutboxId() { return sourceOutboxId; }
    public CancelOutboxRedriveStatus getStatus() { return status; }
    public CancelOutboxRedriveFailureStage getFailureStage() { return failureStage; }
    public String getRequestedBy() { return requestedBy; }
    public String getReason() { return reason; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getResult() { return result; }
    public String getLastError() { return lastError; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
}
