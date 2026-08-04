package com.example.payment.domain.entity;

public enum CancelApprovalStatus {
    REQUESTED, APPROVED, REJECTED;

    public boolean isTerminal() { return this == APPROVED || this == REJECTED; }
}
