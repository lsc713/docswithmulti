package com.example.payment.domain.entity;

/**
 * PaymentItem 상태 enum
 *
 * ACTIVE → PARTIAL_CANCELLED → CANCELLED
 *       ↘ CANCELLED (직접)
 */
public enum PaymentItemStatus {
    ACTIVE("활성"),
    PARTIAL_CANCELLED("부분 취소"),
    CANCELLED("전액 취소");

    private final String description;

    PaymentItemStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 취소 가능한 상태인지 확인
     */
    public boolean isCancellable() {
        return this != CANCELLED;
    }

    /**
     * 최종 상태인지 확인
     */
    public boolean isFinal() {
        return this == CANCELLED;
    }
}
