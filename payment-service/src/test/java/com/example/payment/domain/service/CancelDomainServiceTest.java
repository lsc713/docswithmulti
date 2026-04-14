package com.example.payment.domain.service;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.domain.exception.CancelAmountExceededException;
import com.example.payment.domain.exception.CancelPeriodExceededException;
import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import com.example.payment.domain.exception.InvalidPaymentStatusException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.fixture.PaymentFixture;
import com.example.payment.fixture.PaymentItemFixture;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelDomainService 테스트")
class CancelDomainServiceTest {

    private Payment payment;
    private Clock clock;
    private CancelPeriodPolicy cancelPeriodPolicy;
    private CancelDomainService cancelDomainService;

    @BeforeEach
    void setUp() {
        // 기본 픽스처
        payment = PaymentFixture.completedPayment();

        // Clock 고정: 2026-03-15 (결제일 2026-01-01 + 90일 이내)
        clock = Clock.fixed(
            LocalDateTime.of(2026, 3, 15, 0, 0, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC
        );
        cancelPeriodPolicy = new CancelPeriodPolicy(clock);
        cancelDomainService = new CancelDomainService(cancelPeriodPolicy);
    }

    @Nested
    @DisplayName("Payment 상태 검증 실패")
    class WhenPaymentNotCancellable {

        @Test
        @DisplayName("Payment 상태가 PENDING이면 InvalidPaymentStatusException 발생")
        void should_throw_when_payment_status_is_pending() {
            Payment pendingPayment = PaymentFixture.pendingPayment();
            List<PaymentItem> items = List.of(PaymentItemFixture.activeItem(1L, 100L));
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(5000)));

            assertThrows(
                InvalidPaymentStatusException.class,
                () -> cancelDomainService.apply(pendingPayment, commands, items)
            );
        }

        @Test
        @DisplayName("Payment 상태가 CANCELLED이면 InvalidPaymentStatusException 발생")
        void should_throw_when_payment_status_is_cancelled() {
            payment.updateStatus(PaymentStatus.CANCELLED);
            List<PaymentItem> items = List.of(PaymentItemFixture.activeItem(1L, 100L));
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(5000)));

            assertThrows(
                InvalidPaymentStatusException.class,
                () -> cancelDomainService.apply(payment, commands, items)
            );
        }

        @Test
        @DisplayName("Payment 상태가 CANCEL_FAILED이면 InvalidPaymentStatusException 발생")
        void should_throw_when_payment_status_is_cancel_failed() {
            payment.updateStatus(PaymentStatus.CANCEL_FAILED);
            List<PaymentItem> items = List.of(PaymentItemFixture.activeItem(1L, 100L));
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(5000)));

            assertThrows(
                InvalidPaymentStatusException.class,
                () -> cancelDomainService.apply(payment, commands, items)
            );
        }
    }

    @Nested
    @DisplayName("취소 기간 초과")
    class WhenCancelPeriodExceeded {

        @Test
        @DisplayName("취소 기간 초과 시 CancelPeriodExceededException 발생")
        void should_throw_when_cancel_period_exceeded() {
            // Clock을 기간 이후로 설정 (2026-04-13, 90일 초과)
            Clock expiredClock = Clock.fixed(
                LocalDateTime.of(2026, 4, 13, 0, 0, 0).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
            );
            CancelDomainService expiredService = new CancelDomainService(new CancelPeriodPolicy(expiredClock));

            List<PaymentItem> items = List.of(PaymentItemFixture.activeItem(1L, 100L));
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(5000)));

            assertThrows(
                CancelPeriodExceededException.class,
                () -> expiredService.apply(payment, commands, items)
            );
        }
    }

    @Nested
    @DisplayName("PaymentItem 상태 검증 실패")
    class WhenPaymentItemNotCancellable {

        @Test
        @DisplayName("취소 대상 항목이 이미 CANCELLED 상태면 InvalidPaymentItemStatusException 발생")
        void should_throw_when_target_item_is_already_cancelled() {
            PaymentItem cancelledItem = PaymentItemFixture.cancelledItem(1L, 100L);
            List<PaymentItem> items = List.of(cancelledItem);
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(5000)));

            assertThrows(
                InvalidPaymentItemStatusException.class,
                () -> cancelDomainService.apply(payment, commands, items)
            );
        }
    }

    @Nested
    @DisplayName("취소 금액 검증 실패")
    class WhenCancelAmountExceeded {

        @Test
        @DisplayName("항목 취소 금액이 잔여 가용액을 초과하면 CancelAmountExceededException 발생")
        void should_throw_when_item_cancel_amount_exceeds_available() {
            PaymentItem item = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(5000));
            List<PaymentItem> items = List.of(item);
            // 취소 요청: 6000 (가용액 5000 초과)
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(6000)));

            assertThrows(
                CancelAmountExceededException.class,
                () -> cancelDomainService.apply(payment, commands, items)
            );
        }
    }

    @Nested
    @DisplayName("단일 항목 전액 취소 (COMPLETED → CANCELLED)")
    class WhenSingleItemFullCancel {

        @Test
        @DisplayName("단일 항목을 전액 취소하면 항목 상태는 CANCELLED")
        void should_update_payment_item_status_to_cancelled() {
            PaymentItem item = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(10000));
            List<PaymentItem> items = List.of(item);
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(10000)));

            cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentItemStatus.CANCELLED, item.getStatus());
        }

        @Test
        @DisplayName("단일 항목을 전액 취소하면 Payment 상태는 CANCELLED")
        void should_return_cancelled_payment_status() {
            PaymentItem item = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(100000));
            List<PaymentItem> items = List.of(item);
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(100000)));

            PaymentStatus result = cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentStatus.CANCELLED, result);
            assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        }
    }

    @Nested
    @DisplayName("단일 항목 부분 취소 (COMPLETED → PARTIAL_CANCELLED)")
    class WhenSingleItemPartialCancel {

        @Test
        @DisplayName("단일 항목을 부분 취소하면 항목 상태는 PARTIAL_CANCELLED")
        void should_update_payment_item_status_to_partial_cancelled() {
            PaymentItem item = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(10000));
            List<PaymentItem> items = List.of(item);
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(5000)));

            cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentItemStatus.PARTIAL_CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(5000), item.getCancelledAmount());
        }

        @Test
        @DisplayName("단일 항목을 부분 취소하면 Payment 상태는 PARTIAL_CANCELLED")
        void should_return_partial_cancelled_payment_status() {
            PaymentItem item = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(100000));
            List<PaymentItem> items = List.of(item);
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(50000)));

            PaymentStatus result = cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentStatus.PARTIAL_CANCELLED, result);
            assertEquals(PaymentStatus.PARTIAL_CANCELLED, payment.getStatus());
        }
    }

    @Nested
    @DisplayName("복수 항목 전액 취소 (COMPLETED → CANCELLED)")
    class WhenMultipleItemsFullCancel {

        @Test
        @DisplayName("복수 항목을 모두 전액 취소하면 Payment 상태는 CANCELLED")
        void should_return_cancelled_when_all_items_fully_cancelled() {
            PaymentItem item1 = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(50000));
            PaymentItem item2 = PaymentItemFixture.activeItem(1L, 101L, BigDecimal.valueOf(50000));
            List<PaymentItem> items = List.of(item1, item2);
            List<CancelItemCommand> commands = Arrays.asList(
                CancelItemCommand.of(100L, BigDecimal.valueOf(50000)),
                CancelItemCommand.of(101L, BigDecimal.valueOf(50000))
            );

            PaymentStatus result = cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentStatus.CANCELLED, result);
            assertEquals(PaymentItemStatus.CANCELLED, item1.getStatus());
            assertEquals(PaymentItemStatus.CANCELLED, item2.getStatus());
        }
    }

    @Nested
    @DisplayName("복수 항목 부분 취소 (COMPLETED → PARTIAL_CANCELLED)")
    class WhenMultipleItemsPartialCancel {

        @Test
        @DisplayName("일부 항목만 취소하면 취소되지 않은 항목은 변경 없음")
        void should_not_touch_non_targeted_items() {
            PaymentItem item1 = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(50000));
            PaymentItem item2 = PaymentItemFixture.activeItem(1L, 101L, BigDecimal.valueOf(50000));
            List<PaymentItem> items = List.of(item1, item2);
            // item1만 부분 취소
            List<CancelItemCommand> commands = List.of(
                CancelItemCommand.of(100L, BigDecimal.valueOf(25000))
            );

            cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentItemStatus.PARTIAL_CANCELLED, item1.getStatus());
            assertEquals(PaymentItemStatus.ACTIVE, item2.getStatus());
        }

        @Test
        @DisplayName("일부 항목만 부분 취소하면 Payment 상태는 PARTIAL_CANCELLED")
        void should_return_partial_cancelled_when_only_some_items_cancelled() {
            PaymentItem item1 = PaymentItemFixture.activeItem(1L, 100L, BigDecimal.valueOf(50000));
            PaymentItem item2 = PaymentItemFixture.activeItem(1L, 101L, BigDecimal.valueOf(50000));
            List<PaymentItem> items = List.of(item1, item2);
            List<CancelItemCommand> commands = List.of(
                CancelItemCommand.of(100L, BigDecimal.valueOf(25000))
            );

            PaymentStatus result = cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentStatus.PARTIAL_CANCELLED, result);
        }
    }

    @Nested
    @DisplayName("이미 부분취소된 항목에 추가 취소")
    class WhenItemAlreadyPartiallyCancelled {

        @Test
        @DisplayName("추가 부분 취소하면 항목 상태는 PARTIAL_CANCELLED (유지)")
        void should_maintain_partial_cancelled_when_partially_cancelling_again() {
            // 이미 3000 취소된 항목 (잔여: 7000)
            PaymentItem item = PaymentItemFixture.partiallyCancelledItem(1L, 100L, BigDecimal.valueOf(10000), BigDecimal.valueOf(3000));
            List<PaymentItem> items = List.of(item);
            // 추가 3000 취소
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(3000)));

            PaymentStatus result = cancelDomainService.apply(payment, commands, items);

            assertEquals(PaymentItemStatus.PARTIAL_CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(6000), item.getCancelledAmount());
            assertEquals(PaymentStatus.PARTIAL_CANCELLED, result);
        }

        @Test
        @DisplayName("추가로 잔액 전체를 취소하면 항목 상태는 CANCELLED")
        void should_return_cancelled_when_remaining_fully_cancelled() {
            // 이미 3000 취소된 항목 (잔여: 7000)
            PaymentItem item = PaymentItemFixture.partiallyCancelledItem(1L, 100L, BigDecimal.valueOf(10000), BigDecimal.valueOf(3000));
            List<PaymentItem> items = List.of(item);

            // totalAmount가 10,000인 payment 생성 (항목 금액과 일치)
            Payment singleItemPayment = Payment.of(
                "pay_single_item", 1L, 10L, "TOSS",
                BigDecimal.valueOf(10000), "KRW", 90,
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0)
            );

            // 잔액 전체(7000) 취소
            List<CancelItemCommand> commands = List.of(CancelItemCommand.of(100L, BigDecimal.valueOf(7000)));

            PaymentStatus result = cancelDomainService.apply(singleItemPayment, commands, items);

            assertEquals(PaymentItemStatus.CANCELLED, item.getStatus());
            assertEquals(BigDecimal.valueOf(10000), item.getCancelledAmount());
            assertEquals(PaymentStatus.CANCELLED, result);
        }
    }
}
