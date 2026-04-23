package com.example.payment.domain.service;

/**
 * 취소 항목 커맨드
 *
 * 아이템 전액 취소만 지원 (domain-rules.md 1-2).
 * paymentItemId만 필요하며 cancelAmount는 포함하지 않는다.
 */
public final class CancelItemCommand {

    private final long paymentItemId;

    private CancelItemCommand(long paymentItemId) {
        this.paymentItemId = paymentItemId;
    }

    public static CancelItemCommand of(long paymentItemId) {
        return new CancelItemCommand(paymentItemId);
    }

    public long getPaymentItemId() { return paymentItemId; }
}
