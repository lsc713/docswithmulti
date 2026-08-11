package com.example.payment.domain.entity;

public enum CancelOutboxRedriveStatus {
    REQUESTED, REDRIVING, RESOLVED, RESOLVED_ALREADY_APPLIED, REJECTED, FAILED;

    public boolean isActive() {
        return this == REQUESTED || this == REDRIVING;
    }

    public boolean isResolved() {
        return this == RESOLVED || this == RESOLVED_ALREADY_APPLIED;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
