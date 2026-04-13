package com.example.payment.domain.policy;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.exception.CancelAmountExceededException;
import com.example.payment.domain.exception.InvalidCancelAmountException;
import java.math.BigDecimal;

/**
 * 취소 금액 검증 정책 객체
 *
 * domain-rules.md 2-1: 취소 금액 검증
 */
public class CancelAmountPolicy {

    private CancelAmountPolicy() {
    }

    /**
     * 단일 취소 금액 검증
     *
     * @param cancelAmount 취소 요청 금액
     * @throws InvalidCancelAmountException 금액이 1원 미만일 때
     */
    public static void validateCancelAmount(BigDecimal cancelAmount) {
        if (cancelAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidCancelAmountException(cancelAmount);
        }
    }

    /**
     * 항목별 취소 가능액 검증
     *
     * PaymentItem.amount - PaymentItem.cancelled_amount >= cancelItem.cancelAmount
     *
     * @param item 결제 항목
     * @param cancelAmount 요청한 취소 금액
     * @throws CancelAmountExceededException 잔액을 초과할 때
     */
    public static void validateItemCancelAmount(PaymentItem item, BigDecimal cancelAmount) {
        BigDecimal availableAmount = item.getAvailableCancelAmount();

        if (cancelAmount.compareTo(availableAmount) > 0) {
            throw new CancelAmountExceededException(
                item.getOrderItemId(),
                cancelAmount,
                availableAmount
            );
        }
    }
}
