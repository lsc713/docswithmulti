package com.example.payment.fixture;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment 도메인 엔티티 테스트 픽스처
 *
 * 테스트에서 공통으로 사용되는 Payment 객체를 생성한다.
 * Payment의 정적 팩토리 메서드를 활용한다.
 */
public class PaymentFixture {

    /**
     * 기본 완료 결제 (COMPLETED 상태)
     * - 결제일: 2026-01-01 00:00:00 UTC
     * - 총액: 100,000원
     * - 취소 기간: 90일
     */
    public static Payment completedPayment() {
        return Payment.of(
            "pay_test_001",
            1L,
            1L,
            "TOSS",
            BigDecimal.valueOf(100000),
            "KRW",
            90,
            LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        );
    }

    /**
     * 취소 기간이 1일인 완료 결제
     * - 결제일: 2026-01-01 00:00:00 UTC
     * - 취소 기간: 1일 (만료일: 2026-01-02)
     */
    public static Payment completedPaymentWith1DayPeriod() {
        return Payment.of(
            "pay_test_002",
            1L,
            1L,
            "TOSS",
            BigDecimal.valueOf(100000),
            "KRW",
            1,
            LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        );
    }

    /**
     * 보류 중 결제 (PENDING 상태)
     */
    public static Payment pendingPayment() {
        return Payment.ofPending(
            "pay_test_pending",
            1L,
            1L,
            "TOSS",
            BigDecimal.valueOf(100000),
            "KRW",
            90
        );
    }

    /**
     * 전액 취소된 결제 (CANCELLED 상태)
     * - 결제일: 2026-01-01 00:00:00 UTC
     * - 총액: 100,000원
     * - 취소 기간: 90일
     */
    public static Payment cancelledPayment() {
        Payment payment = Payment.of(
            "pay_test_cancelled",
            1L,
            1L,
            "TOSS",
            BigDecimal.valueOf(100000),
            "KRW",
            90,
            LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        );
        payment.updateStatus(PaymentStatus.CANCELLED);
        return payment;
    }

    private PaymentFixture() {
    }
}
