package com.example.payment.domain.entity;

public enum CancelStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isProcessing() {
        return this == PROCESSING;
    }
}
