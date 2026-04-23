package com.example.payment.domain.service;

import com.example.payment.domain.entity.*;
import com.example.payment.domain.exception.PaymentItemNotFoundException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.policy.PaymentItemStatusPolicy;
import com.example.payment.domain.policy.PaymentStatusPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 취소 도메인 서비스
 *
 * 검증 → 대상 항목 전액 취소 → Payment 상태 재계산
 * CancelRequest 상태 전이는 포함하지 않음 (Application 레이어 책임)
 *
 * domain-rules.md 4-1, 4-2: 상태 전이 규칙
 */
public class CancelDomainService {

    private final CancelPeriodPolicy cancelPeriodPolicy;

    public CancelDomainService(CancelPeriodPolicy cancelPeriodPolicy) {
        this.cancelPeriodPolicy = cancelPeriodPolicy;
    }

    /**
     * @param payment         대상 결제
     * @param cancelItems     취소할 paymentItemId 목록
     * @param allPaymentItems Payment에 속한 전체 PaymentItem (FOR UPDATE 재조회 결과)
     * @return 취소 후 Payment 신규 상태
     */
    public PaymentStatus apply(
        Payment payment,
        List<CancelItemCommand> cancelItems,
        List<PaymentItem> allPaymentItems
    ) {
        PaymentStatusPolicy.validateCancellableStatus(payment);
        cancelPeriodPolicy.validateCancelPeriod(payment);

        Set<Long> targetIds = cancelItems.stream()
            .map(CancelItemCommand::getPaymentItemId)
            .collect(Collectors.toSet());

        Map<Long, PaymentItem> itemMap = allPaymentItems.stream()
            .collect(Collectors.toMap(PaymentItem::getId, i -> i));

        for (Long targetId : targetIds) {
            PaymentItem item = itemMap.get(targetId);
            if (item == null) throw new PaymentItemNotFoundException(targetId);
            PaymentItemStatusPolicy.validateCancellableStatus(item);
            item.cancel();
        }

        return recalculatePaymentStatus(payment, allPaymentItems);
    }

    private PaymentStatus recalculatePaymentStatus(Payment payment, List<PaymentItem> allItems) {
        BigDecimal cancelledTotal = allItems.stream()
            .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED)
            .map(PaymentItem::getItemAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentStatus newStatus = cancelledTotal.compareTo(payment.getTotalAmount()) >= 0
            ? PaymentStatus.CANCELLED
            : PaymentStatus.PARTIAL_CANCELLED;

        payment.updateStatus(newStatus);
        return newStatus;
    }
}
