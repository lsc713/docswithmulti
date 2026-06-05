package com.example.payment.domain.service;

import com.example.payment.domain.entity.*;
import com.example.payment.common.exception.domain.InvalidPaymentItemStatusException;
import com.example.payment.common.exception.domain.InvalidPaymentStatusException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelDomainService")
class CancelDomainServiceTest {

    private CancelDomainService service;

    /** 2026-03-01: PaymentFixture.completedPayment() 결제일(2026-01-01) 기준 90일 이내 */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        service = new CancelDomainService(new CancelPeriodPolicy(clock));
    }

    private static PaymentItem activeItem(long id, long paymentId, BigDecimal amount) {
        return PaymentItem.reconstruct(id, paymentId, 10L, 100L, 200L, "상품", amount,
            PaymentItemStatus.ACTIVE);
    }

    private static PaymentItem cancelledItem(long id, long paymentId, BigDecimal amount) {
        return PaymentItem.reconstruct(id, paymentId, 10L, 100L, 200L, "상품", amount,
            PaymentItemStatus.CANCELLED);
    }

    @Test
    @DisplayName("should_cancel_target_items_and_set_payment_partial_cancelled")
    void shouldCancelTargetItemsAndSetPaymentPartialCancelled() {
        Payment payment = PaymentFixture.completedPayment(); // totalAmount=100000
        PaymentItem itemA = activeItem(1L, payment.getId(), BigDecimal.valueOf(30000));
        PaymentItem itemB = activeItem(2L, payment.getId(), BigDecimal.valueOf(70000));

        PaymentStatus newStatus = service.apply(payment,
            List.of(CancelItemCommand.of(1L)),
            List.of(itemA, itemB));

        assertEquals(PaymentItemStatus.CANCELLED, itemA.getStatus());
        assertEquals(PaymentItemStatus.ACTIVE, itemB.getStatus());
        assertEquals(PaymentStatus.PARTIAL_CANCELLED, newStatus);
    }

    @Test
    @DisplayName("should_set_payment_cancelled_when_all_items_cancelled")
    void shouldSetPaymentCancelledWhenAllItemsCancelled() {
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem itemA = activeItem(1L, payment.getId(), BigDecimal.valueOf(30000));
        PaymentItem itemB = activeItem(2L, payment.getId(), BigDecimal.valueOf(70000));

        PaymentStatus newStatus = service.apply(payment,
            List.of(CancelItemCommand.of(1L), CancelItemCommand.of(2L)),
            List.of(itemA, itemB));

        assertEquals(PaymentStatus.CANCELLED, newStatus);
    }

    @Test
    @DisplayName("should_throw_when_payment_not_cancellable")
    void shouldThrowWhenPaymentNotCancellable() {
        Payment cancelled = PaymentFixture.cancelledPayment();
        PaymentItem item = activeItem(1L, cancelled.getId(), BigDecimal.valueOf(30000));

        assertThrows(InvalidPaymentStatusException.class,
            () -> service.apply(cancelled, List.of(CancelItemCommand.of(1L)), List.of(item)));
    }

    @Test
    @DisplayName("should_throw_when_target_item_already_cancelled")
    void shouldThrowWhenTargetItemAlreadyCancelled() {
        Payment payment = PaymentFixture.completedPayment();
        PaymentItem cancelledItem = cancelledItem(1L, payment.getId(), BigDecimal.valueOf(30000));

        assertThrows(InvalidPaymentItemStatusException.class,
            () -> service.apply(payment, List.of(CancelItemCommand.of(1L)), List.of(cancelledItem)));
    }
}
