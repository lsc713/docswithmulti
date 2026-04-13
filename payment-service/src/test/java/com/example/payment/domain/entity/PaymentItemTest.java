package com.example.payment.domain.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.exception.CancelAmountExceededException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentItem 도메인 엔티티")
class PaymentItemTest {

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("PaymentItem을 정상적으로 생성한다")
        void should_create_payment_item_with_valid_parameters() {
            // given
            long paymentId = 1L;
            long orderItemId = 100L;
            BigDecimal itemAmount = BigDecimal.valueOf(100000);

            // when
            PaymentItem item = PaymentItem.of(
                paymentId,
                orderItemId,
                1L,
                1L,
                "상품명",
                itemAmount
            );

            // then
            assertEquals(orderItemId, item.getOrderItemId());
            assertEquals(itemAmount, item.getItemAmount());
            assertEquals(BigDecimal.ZERO, item.getCancelledAmount());
            assertEquals(PaymentItemStatus.ACTIVE, item.getStatus());
        }

        @Test
        @DisplayName("취소 가능액을 정확히 계산한다")
        void should_calculate_available_cancel_amount_correctly() {
            // given
            PaymentItem item = PaymentItem.of(
                1L,
                100L,
                1L,
                1L,
                "상품명",
                BigDecimal.valueOf(100000)
            );

            // when
            BigDecimal availableAmount = item.getAvailableCancelAmount();

            // then
            assertEquals(BigDecimal.valueOf(100000), availableAmount);
        }
    }

    @Nested
    @DisplayName("ACTIVE 상태일 때")
    class WhenActive {

        private PaymentItem item;

        @BeforeEach
        void setUp() {
            item = PaymentItem.of(
                1L,
                100L,
                1L,
                1L,
                "상품명",
                BigDecimal.valueOf(100000)
            );
        }

        @Test
        @DisplayName("취소 가능하다")
        void should_be_cancellable() {
            assertTrue(item.isCancellable());
        }

        @Test
        @DisplayName("부분 취소하면 PARTIAL_CANCELLED 상태가 된다")
        void should_transition_to_partial_cancelled() {
            // when
            item.cancelPartially(BigDecimal.valueOf(30000));

            // then
            assertEquals(PaymentItemStatus.PARTIAL_CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(30000), item.getCancelledAmount());
            assertEquals(BigDecimal.valueOf(70000), item.getAvailableCancelAmount());
        }

        @Test
        @DisplayName("전액 취소하면 CANCELLED 상태가 된다")
        void should_transition_to_cancelled_with_full_amount() {
            // when
            item.cancelPartially(BigDecimal.valueOf(100000));

            // then
            assertEquals(PaymentItemStatus.CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(100000), item.getCancelledAmount());
        }

        @Test
        @DisplayName("취소 가능액을 초과하면 예외를 발생시킨다")
        void should_reject_cancel_amount_exceeding_available() {
            // when & then
            CancelAmountExceededException exception = assertThrows(
                CancelAmountExceededException.class,
                () -> item.cancelPartially(BigDecimal.valueOf(100001))
            );

            assertEquals(ErrorCode.CANCEL_AMOUNT_EXCEEDED, exception.getErrorCode());
            assertEquals(BigDecimal.valueOf(100001), exception.getRequestedAmount());
            assertEquals(BigDecimal.valueOf(100000), exception.getAvailableAmount());
        }

        @Test
        @DisplayName("0원 취소는 예외를 발생시킨다")
        void should_reject_zero_cancel_amount() {
            // when & then
            assertThrows(
                CancelAmountExceededException.class,
                () -> item.cancelPartially(BigDecimal.ZERO)
            );
        }

        @Test
        @DisplayName("음수 취소는 예외를 발생시킨다")
        void should_reject_negative_cancel_amount() {
            // when & then
            assertThrows(
                CancelAmountExceededException.class,
                () -> item.cancelPartially(BigDecimal.valueOf(-1))
            );
        }
    }

    @Nested
    @DisplayName("PARTIAL_CANCELLED 상태일 때")
    class WhenPartialCancelled {

        private PaymentItem item;

        @BeforeEach
        void setUp() {
            item = PaymentItem.of(
                1L,
                100L,
                1L,
                1L,
                "상품명",
                BigDecimal.valueOf(100000)
            );
            item.cancelPartially(BigDecimal.valueOf(30000));
        }

        @Test
        @DisplayName("취소 가능하다")
        void should_be_cancellable() {
            assertTrue(item.isCancellable());
        }

        @Test
        @DisplayName("추가 부분 취소 가능하다")
        void should_allow_additional_partial_cancel() {
            // when
            item.cancelPartially(BigDecimal.valueOf(20000));

            // then
            assertEquals(PaymentItemStatus.PARTIAL_CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(50000), item.getCancelledAmount());
        }

        @Test
        @DisplayName("잔액 전체 취소하면 CANCELLED 상태가 된다")
        void should_transition_to_cancelled_with_remaining_amount() {
            // when
            item.cancelPartially(BigDecimal.valueOf(70000));

            // then
            assertEquals(PaymentItemStatus.CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(100000), item.getCancelledAmount());
        }

        @Test
        @DisplayName("취소 가능액을 초과하면 예외를 발생시킨다")
        void should_reject_cancel_amount_exceeding_available() {
            // when & then
            CancelAmountExceededException exception = assertThrows(
                CancelAmountExceededException.class,
                () -> item.cancelPartially(BigDecimal.valueOf(70001))
            );

            assertEquals(ErrorCode.CANCEL_AMOUNT_EXCEEDED, exception.getErrorCode());
            assertEquals(BigDecimal.valueOf(70001), exception.getRequestedAmount());
            assertEquals(BigDecimal.valueOf(70000), exception.getAvailableAmount());
        }
    }

    @Nested
    @DisplayName("CANCELLED 상태일 때")
    class WhenCancelled {

        private PaymentItem item;

        @BeforeEach
        void setUp() {
            item = PaymentItem.of(
                1L,
                100L,
                1L,
                1L,
                "상품명",
                BigDecimal.valueOf(100000)
            );
            item.cancelPartially(BigDecimal.valueOf(100000));
        }

        @Test
        @DisplayName("취소 불가능하다")
        void should_not_be_cancellable() {
            assertFalse(item.isCancellable());
        }

        @Test
        @DisplayName("재취소하면 예외를 발생시킨다")
        void should_reject_cancel_on_already_cancelled_item() {
            // when & then
            CancelAmountExceededException exception = assertThrows(
                CancelAmountExceededException.class,
                () -> item.cancelPartially(BigDecimal.valueOf(1))
            );

            assertEquals(ErrorCode.CANCEL_AMOUNT_EXCEEDED, exception.getErrorCode());
        }
    }
}
