package com.example.payment.fixture;

import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;

/**
 * PaymentItem 도메인 엔티티 테스트 픽스처
 *
 * 테스트에서 공통으로 사용되는 PaymentItem 객체를 생성한다.
 * PaymentItem의 정적 팩토리 메서드를 활용한다.
 */
public class PaymentItemFixture {

    /**
     * ACTIVE 상태 항목 (취소되지 않은 상태)
     * - orderItemId: 전달된 값
     * - itemAmount: 10,000원
     * - cancelled_amount: 0
     */
    public static PaymentItem activeItem(long paymentId, long orderItemId) {
        return PaymentItem.of(
            paymentId,
            orderItemId,
            10L,           // productId
            1000L,         // productAutoId
            "테스트상품",
            BigDecimal.valueOf(10000)
        );
    }

    /**
     * ACTIVE 상태 항목 (커스텀 금액)
     */
    public static PaymentItem activeItem(long paymentId, long orderItemId, BigDecimal itemAmount) {
        return PaymentItem.of(
            paymentId,
            orderItemId,
            10L,
            1000L,
            "테스트상품",
            itemAmount
        );
    }

    /**
     * PARTIAL_CANCELLED 상태 항목 (일부 취소됨)
     * - itemAmount: 10,000원
     * - cancelled_amount: 3,000원
     */
    public static PaymentItem partiallyCancelledItem(long paymentId, long orderItemId) {
        PaymentItem item = activeItem(paymentId, orderItemId);
        item.cancelPartially(BigDecimal.valueOf(3000));
        return item;
    }

    /**
     * PARTIAL_CANCELLED 상태 항목 (커스텀 취소액)
     */
    public static PaymentItem partiallyCancelledItem(
        long paymentId,
        long orderItemId,
        BigDecimal itemAmount,
        BigDecimal cancelledAmount
    ) {
        PaymentItem item = activeItem(paymentId, orderItemId, itemAmount);
        item.cancelPartially(cancelledAmount);
        return item;
    }

    /**
     * CANCELLED 상태 항목 (전액 취소됨)
     * - itemAmount: 10,000원
     * - cancelled_amount: 10,000원
     */
    public static PaymentItem cancelledItem(long paymentId, long orderItemId) {
        PaymentItem item = activeItem(paymentId, orderItemId);
        item.cancelPartially(BigDecimal.valueOf(10000));
        return item;
    }

    /**
     * CANCELLED 상태 항목 (커스텀 금액)
     */
    public static PaymentItem cancelledItem(long paymentId, long orderItemId, BigDecimal itemAmount) {
        PaymentItem item = activeItem(paymentId, orderItemId, itemAmount);
        item.cancelPartially(itemAmount);
        return item;
    }

    private PaymentItemFixture() {
    }
}
