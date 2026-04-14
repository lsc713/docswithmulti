package com.example.payment.domain.service;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.domain.exception.PaymentItemNotFoundException;
import com.example.payment.domain.policy.CancelAmountPolicy;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.policy.PaymentItemStatusPolicy;
import com.example.payment.domain.policy.PaymentStatusPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 취소 도메인 서비스
 *
 * Payment, PaymentItem 간의 취소 비즈니스 연산을 조율한다.
 * 검증 → 항목 취소 처리 → Payment 상태 결정을 하나의 흐름으로 관리한다.
 *
 * domain-rules.md 4-1, 4-2: 상태 전이 규칙 준수
 * - Payment: COMPLETED/PARTIAL_CANCELLED → CANCELLED/PARTIAL_CANCELLED
 * - PaymentItem: 항목별 상태 전이는 cancelPartially()에서 자동 처리
 *
 * 주의: CancelRequest 상태 전이(PROCESSING → COMPLETED)는 포함하지 않음
 * → Outbox INSERT와 동시 완료 조건이므로 Application 레이어 책임
 */
public class CancelDomainService {

    private final CancelPeriodPolicy cancelPeriodPolicy;

    public CancelDomainService(CancelPeriodPolicy cancelPeriodPolicy) {
        this.cancelPeriodPolicy = cancelPeriodPolicy;
    }

    /**
     * 취소 처리: 검증 → 항목 취소 → Payment 상태 결정
     *
     * @param payment 대상 결제 (COMPLETED 또는 PARTIAL_CANCELLED)
     * @param cancelItems 취소할 항목 목록 (orderItemId, cancelAmount 매핑)
     * @param allPaymentItems Payment에 속한 전체 PaymentItem
     *                        (Payment 상태 전이 판단을 위해 전체 합산 필요)
     * @return 취소 적용 후 결정된 Payment 상태
     */
    public PaymentStatus apply(
        Payment payment,
        List<CancelItemCommand> cancelItems,
        List<PaymentItem> allPaymentItems
    ) {
        // 1단계: Payment 사전 검증
        validatePaymentCancellable(payment);

        // 2단계: 취소 항목 맵핑 (orderItemId → cancelAmount)
        Map<Long, BigDecimal> cancelAmountMap = cancelItems.stream()
            .collect(Collectors.toMap(
                CancelItemCommand::getOrderItemId,
                CancelItemCommand::getCancelAmount
            ));

        // 3단계: 항목별 검증 및 취소 처리
        cancelPaymentItems(allPaymentItems, cancelAmountMap);

        // 4단계: Payment 상태 결정 및 업데이트
        return determineAndUpdatePaymentStatus(payment, allPaymentItems);
    }

    /**
     * Payment 취소 가능 여부 검증
     * - 상태 확인 (COMPLETED, PARTIAL_CANCELLED만 가능)
     * - 취소 기간 확인
     */
    private void validatePaymentCancellable(Payment payment) {
        PaymentStatusPolicy.validateCancellableStatus(payment);
        cancelPeriodPolicy.validateCancelPeriod(payment);
    }

    /**
     * 취소 대상 항목 처리: 검증 → 취소
     */
    private void cancelPaymentItems(
        List<PaymentItem> allPaymentItems,
        Map<Long, BigDecimal> cancelAmountMap
    ) {
        for (Map.Entry<Long, BigDecimal> entry : cancelAmountMap.entrySet()) {
            Long orderItemId = entry.getKey();
            BigDecimal cancelAmount = entry.getValue();

            PaymentItem item = findPaymentItemByOrderItemId(allPaymentItems, orderItemId);

            // 항목 상태 검증
            PaymentItemStatusPolicy.validateCancellableStatus(item);

            // 항목 금액 검증
            CancelAmountPolicy.validateItemCancelAmount(item, cancelAmount);

            // 항목 취소 처리 (상태 자동 전이)
            item.cancelPartially(cancelAmount);
        }
    }

    /**
     * orderItemId로 PaymentItem 찾기
     */
    private PaymentItem findPaymentItemByOrderItemId(List<PaymentItem> items, long orderItemId) {
        return items.stream()
            .filter(item -> item.getOrderItemId() == orderItemId)
            .findFirst()
            .orElseThrow(() ->
                new PaymentItemNotFoundException(orderItemId)
            );
    }

    /**
     * Payment 상태 결정 및 업데이트
     *
     * domain-rules.md 4-1:
     * 취소 후 모든 PaymentItem의 cancelledAmount 합계 = Payment.totalAmount → CANCELLED
     * 미만 → PARTIAL_CANCELLED
     */
    private PaymentStatus determineAndUpdatePaymentStatus(
        Payment payment,
        List<PaymentItem> allPaymentItems
    ) {
        BigDecimal totalCancelled = allPaymentItems.stream()
            .map(PaymentItem::getCancelledAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentStatus newStatus;
        if (totalCancelled.compareTo(payment.getTotalAmount()) >= 0) {
            newStatus = PaymentStatus.CANCELLED;
        } else {
            newStatus = PaymentStatus.PARTIAL_CANCELLED;
        }

        payment.updateStatus(newStatus);
        return newStatus;
    }
}
