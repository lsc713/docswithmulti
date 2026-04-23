package com.example.payment.domain.entity;

public enum PaymentItemStatus {
    ACTIVE("활성"),
    CANCELLED("전액 취소");

    private final String description;

    PaymentItemStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    public boolean isCancellable() { return this == ACTIVE; }

    public boolean isFinal() { return this == CANCELLED; }
}
