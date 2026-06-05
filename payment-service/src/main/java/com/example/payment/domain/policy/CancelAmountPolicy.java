package com.example.payment.domain.policy;

import com.example.payment.common.exception.domain.InvalidCancelAmountException;
import java.math.BigDecimal;

/**
 * 취소 금액 검증 정책 객체
 *
 * domain-rules.md 2-1: 취소 금액 검증
 * 아이템 단위 전액 취소만 지원하므로 항목별 금액 검증은 하지 않는다.
 */
public class CancelAmountPolicy {

    private CancelAmountPolicy() {}

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
}
