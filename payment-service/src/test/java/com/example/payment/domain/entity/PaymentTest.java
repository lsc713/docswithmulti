package com.example.payment.domain.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.common.exception.domain.CancelNotAllowedException;
import com.example.payment.common.exception.domain.CancelPeriodExceededException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Payment 도메인 엔티티")
class PaymentTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("Payment를 정상적으로 생성한다")
        void should_create_payment_with_valid_parameters() {
            // given
            String paymentKey = "pay_test_123";
            long merchantId = 1L;
            long userId = 100L;
            BigDecimal totalAmount = BigDecimal.valueOf(100000);

            // when
            Payment payment = Payment.of(
                paymentKey,
                merchantId,
                userId,
                "CREDIT_CARD",
                totalAmount,
                "KRW",
                90
            );

            // then
            assertEquals(paymentKey, payment.getPaymentKey());
            assertEquals(merchantId, payment.getMerchantId());
            assertEquals(userId, payment.getUserId());
            assertEquals(totalAmount, payment.getTotalAmount());
            assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
            assertEquals(90, payment.getCancelPeriodDays());
        }
    }

    @Nested
    @DisplayName("취소 가능한 상태 (COMPLETED, PARTIAL_CANCELLED)")
    class WhenCancellable {

        private Payment completedPayment;
        private Payment partialCancelledPayment;

        @BeforeEach
        void setUp() {
            completedPayment = Payment.of(
                "pay_test_1",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );

            partialCancelledPayment = Payment.of(
                "pay_test_2",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );
            partialCancelledPayment.updateStatus(PaymentStatus.PARTIAL_CANCELLED);
        }

        @Test
        @DisplayName("COMPLETED 상태는 취소 가능하다")
        void should_allow_cancel_on_completed() {
            assertTrue(completedPayment.canBeCancelled());
        }

        @Test
        @DisplayName("PARTIAL_CANCELLED 상태도 취소 가능하다")
        void should_allow_cancel_on_partial_cancelled() {
            assertTrue(partialCancelledPayment.canBeCancelled());
        }

        @Test
        @DisplayName("취소 가능 상태 검증을 통과한다")
        void should_pass_cancellable_validation() {
            assertDoesNotThrow(completedPayment::validateCancellable);
            assertDoesNotThrow(partialCancelledPayment::validateCancellable);
        }

        @Test
        @DisplayName("취소 기간 내이면 취소 기간 검증을 통과한다")
        void should_pass_cancel_period_validation_within_period() {
            // given
            LocalDateTime createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minus(10, ChronoUnit.DAYS);
            completedPayment.updateCreatedAt(createdAt);

            // when & then
            assertDoesNotThrow(completedPayment::validateCancelPeriod);
        }

        @Test
        @DisplayName("상태를 다른 상태로 업데이트할 수 있다")
        void should_update_status() {
            // when
            completedPayment.updateStatus(PaymentStatus.PARTIAL_CANCELLED);

            // then
            assertEquals(PaymentStatus.PARTIAL_CANCELLED, completedPayment.getStatus());
        }
    }

    @Nested
    @DisplayName("취소 불가능한 상태 (PENDING, CANCELLED, CANCEL_FAILED)")
    class WhenNotCancellable {

        private Payment pendingPayment;
        private Payment cancelledPayment;
        private Payment cancelFailedPayment;

        @BeforeEach
        void setUp() {
            pendingPayment = Payment.ofPending(
                "pay_test_1",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );

            cancelledPayment = Payment.of(
                "pay_test_2",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );
            cancelledPayment.updateStatus(PaymentStatus.CANCELLED);

            cancelFailedPayment = Payment.of(
                "pay_test_3",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );
            cancelFailedPayment.updateStatus(PaymentStatus.CANCEL_FAILED);
        }

        @Test
        @DisplayName("PENDING 상태는 취소 불가능하다")
        void should_reject_cancel_on_pending() {
            assertFalse(pendingPayment.canBeCancelled());
        }

        @Test
        @DisplayName("CANCELLED 상태는 취소 불가능하다")
        void should_reject_cancel_on_cancelled() {
            assertFalse(cancelledPayment.canBeCancelled());
        }

        @Test
        @DisplayName("CANCEL_FAILED 상태는 취소 불가능하다")
        void should_reject_cancel_on_cancel_failed() {
            assertFalse(cancelFailedPayment.canBeCancelled());
        }

        @Test
        @DisplayName("PENDING 상태 취소 검증 시 예외를 발생시킨다")
        void should_throw_exception_on_pending_cancel_validation() {
            CancelNotAllowedException exception = assertThrows(
                CancelNotAllowedException.class,
                pendingPayment::validateCancellable
            );
            assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, exception.getErrorCode());
            assertEquals(PaymentStatus.PENDING, exception.getCurrentStatus());
        }

        @Test
        @DisplayName("CANCELLED 상태 취소 검증 시 예외를 발생시킨다")
        void should_throw_exception_on_cancelled_cancel_validation() {
            CancelNotAllowedException exception = assertThrows(
                CancelNotAllowedException.class,
                cancelledPayment::validateCancellable
            );
            assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, exception.getErrorCode());
        }

        @Test
        @DisplayName("CANCEL_FAILED 상태 취소 검증 시 예외를 발생시킨다")
        void should_throw_exception_on_cancel_failed_cancel_validation() {
            CancelNotAllowedException exception = assertThrows(
                CancelNotAllowedException.class,
                cancelFailedPayment::validateCancellable
            );
            assertEquals(ErrorCode.INVALID_PAYMENT_STATUS, exception.getErrorCode());
        }
    }

    @Nested
    @DisplayName("취소 기간")
    class CancelPeriod {

        private Payment payment;

        @BeforeEach
        void setUp() {
            payment = Payment.of(
                "pay_test",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );
        }

        @Test
        @DisplayName("취소 기간 내이면 검증을 통과한다")
        void should_pass_validation_within_period() {
            // given
            LocalDateTime createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minus(10, ChronoUnit.DAYS);
            payment.updateCreatedAt(createdAt);

            // when & then
            assertDoesNotThrow(payment::validateCancelPeriod);
        }

        @Test
        @DisplayName("취소 기간을 초과하면 예외를 발생시킨다")
        void should_reject_cancel_exceeding_period() {
            // given
            LocalDateTime createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minus(91, ChronoUnit.DAYS);
            payment.updateCreatedAt(createdAt);

            // when & then
            CancelPeriodExceededException exception = assertThrows(
                CancelPeriodExceededException.class,
                payment::validateCancelPeriod
            );
            assertEquals(ErrorCode.CANCEL_PERIOD_EXCEEDED, exception.getErrorCode());
            assertEquals(90, exception.getCancelPeriodDays());
        }

        @Test
        @DisplayName("기간이 정확히 경과했을 때 예외를 발생시킨다")
        void should_reject_cancel_at_exact_boundary() {
            // given
            LocalDateTime createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minus(90, ChronoUnit.DAYS)
                .minus(1, ChronoUnit.SECONDS);
            payment.updateCreatedAt(createdAt);

            // when & then
            assertThrows(
                CancelPeriodExceededException.class,
                payment::validateCancelPeriod
            );
        }
    }

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("동일 객체는 equal이다")
        void sameInstance_isEqual() {
            Payment p = Payment.of("key1", 1L, 1L, "TOSS", BigDecimal.valueOf(10000), "KRW", 90);
            assertEquals(p, p);
        }

        @Test
        @DisplayName("null과는 equal이 아니다")
        void nullIsNotEqual() {
            Payment p = Payment.of("key1", 1L, 1L, "TOSS", BigDecimal.valueOf(10000), "KRW", 90);
            assertNotEquals(null, p);
        }

        @Test
        @DisplayName("다른 클래스와는 equal이 아니다")
        void differentClassIsNotEqual() {
            Payment p = Payment.of("key1", 1L, 1L, "TOSS", BigDecimal.valueOf(10000), "KRW", 90);
            assertNotEquals("string", p);
        }

        @Test
        @DisplayName("같은 id·paymentKey이면 equal이다")
        void sameIdAndKey_isEqual() {
            Payment p1 = Payment.reconstruct(1L, "key1", 1L, 1L, "TOSS",
                BigDecimal.valueOf(10000), "KRW", 90, PaymentStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
            Payment p2 = Payment.reconstruct(1L, "key1", 2L, 2L, "KG",
                BigDecimal.valueOf(99999), "USD", 30, PaymentStatus.CANCELLED,
                LocalDateTime.now(), LocalDateTime.now());
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        @DisplayName("id가 다르면 equal이 아니다")
        void differentId_isNotEqual() {
            Payment p1 = Payment.reconstruct(1L, "key1", 1L, 1L, "TOSS",
                BigDecimal.valueOf(10000), "KRW", 90, PaymentStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
            Payment p2 = Payment.reconstruct(2L, "key1", 1L, 1L, "TOSS",
                BigDecimal.valueOf(10000), "KRW", 90, PaymentStatus.COMPLETED,
                LocalDateTime.now(), LocalDateTime.now());
            assertNotEquals(p1, p2);
        }
    }

    @Nested
    @DisplayName("잔여 취소 가능액 계산")
    class RemainingCancelAmount {

        private Payment payment;

        @BeforeEach
        void setUp() {
            payment = Payment.of(
                "pay_test",
                1L,
                100L,
                "CREDIT_CARD",
                BigDecimal.valueOf(100000),
                "KRW",
                90
            );
        }

        @Test
        @DisplayName("초기 잔여액은 전체 금액과 같다")
        void should_calculate_remaining_amount_correctly() {
            // when
            BigDecimal remaining = payment.calculateRemainingCancelAmount(BigDecimal.ZERO);

            // then
            assertEquals(BigDecimal.valueOf(100000), remaining);
        }

        @Test
        @DisplayName("취소액을 차감한 후 잔여액을 정확히 계산한다")
        void should_calculate_remaining_amount_after_cancel() {
            // when
            BigDecimal remaining = payment.calculateRemainingCancelAmount(BigDecimal.valueOf(30000));

            // then
            assertEquals(BigDecimal.valueOf(70000), remaining);
        }

        @Test
        @DisplayName("여러 번 취소 후 누적된 잔여액을 계산한다")
        void should_calculate_remaining_amount_after_multiple_cancels() {
            // when
            BigDecimal remaining1 = payment.calculateRemainingCancelAmount(BigDecimal.valueOf(30000));
            BigDecimal remaining2 = payment.calculateRemainingCancelAmount(
                remaining1.subtract(BigDecimal.valueOf(20000))
            );

            // then
            assertEquals(BigDecimal.valueOf(70000), remaining1);
            assertEquals(BigDecimal.valueOf(50000), remaining2);
        }
    }
}
