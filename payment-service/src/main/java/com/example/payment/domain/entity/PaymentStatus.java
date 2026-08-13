package com.example.payment.domain.entity;

/**
 * Payment 상태 enum
 *
 * PENDING → COMPLETED → PARTIAL_CANCELLED → CANCELLED
 *                    ↘ CANCELLED (직접)
 * CANCEL_FAILED: 취소 실패 상태
 */
public enum PaymentStatus {
    PENDING("진행 중"),
    COMPLETED("결제 완료"),
    PARTIAL_CANCELLED("부분 취소"),
    CANCELLED("전액 취소"),
    FAILED("결제 실패"),
    CANCEL_FAILED("취소 실패");

    private final String description;

    PaymentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 취소 가능한 상태인지 확인
     */
    public boolean isCancellable() {
        return this == COMPLETED || this == PARTIAL_CANCELLED;
    }

    /**
     * 최종 상태인지 확인 (더 이상 변경 불가)
     */
    public boolean isFinal() {
        return this == CANCELLED || this == FAILED || this == CANCEL_FAILED;
    }
}
