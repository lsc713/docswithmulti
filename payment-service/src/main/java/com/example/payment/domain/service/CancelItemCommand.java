package com.example.payment.domain.service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 취소 항목 커맨드
 *
 * CancelDomainService에 전달되는 불변 값객체.
 * orderItemId와 취소 금액을 매핑한다.
 *
 * domain-rules.md 2-3: 부분취소 금액 조건
 * - cancelAmount는 1원 이상이어야 한다 (검증은 Service에서 수행)
 * - 중복된 orderItemId는 없어야 한다 (Application에서 검증)
 */
public final class CancelItemCommand {

    private final long orderItemId;
    private final BigDecimal cancelAmount;

    private CancelItemCommand(long orderItemId, BigDecimal cancelAmount) {
        this.orderItemId = orderItemId;
        this.cancelAmount = cancelAmount;
    }

    /**
     * 취소 항목 커맨드 생성
     *
     * @param orderItemId 주문 항목 ID
     * @param cancelAmount 취소 금액
     */
    public static CancelItemCommand of(long orderItemId, BigDecimal cancelAmount) {
        return new CancelItemCommand(orderItemId, cancelAmount);
    }

    public long getOrderItemId() {
        return orderItemId;
    }

    public BigDecimal getCancelAmount() {
        return cancelAmount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CancelItemCommand that = (CancelItemCommand) o;
        return orderItemId == that.orderItemId && Objects.equals(cancelAmount, that.cancelAmount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderItemId, cancelAmount);
    }

    @Override
    public String toString() {
        return "CancelItemCommand{" +
            "orderItemId=" + orderItemId +
            ", cancelAmount=" + cancelAmount +
            '}';
    }
}
