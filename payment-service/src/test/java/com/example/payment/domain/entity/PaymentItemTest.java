package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentItem 도메인 엔티티")
class PaymentItemTest {

    private PaymentItem item;

    @BeforeEach
    void setUp() {
        item = PaymentItem.of(1L, 10L, 100L, 200L, "상품A", BigDecimal.valueOf(30000));
    }

    @Nested
    @DisplayName("ACTIVE 상태일 때")
    class WhenActive {

        @Test
        @DisplayName("should_cancel_item_and_transition_to_cancelled")
        void shouldCancelItemAndTransitionToCancelled() {
            item.cancel();
            assertEquals(PaymentItemStatus.CANCELLED, item.getStatus());
        }

        @Test
        @DisplayName("should_return_true_for_cancellable")
        void shouldReturnTrueForCancellable() {
            assertTrue(item.isCancellable());
        }
    }

    @Nested
    @DisplayName("CANCELLED 상태일 때")
    class WhenCancelled {

        @BeforeEach
        void cancel() {
            item.cancel();
        }

        @Test
        @DisplayName("should_throw_when_cancel_called_again")
        void shouldThrowWhenCancelCalledAgain() {
            assertThrows(InvalidPaymentItemStatusException.class, item::cancel);
        }

        @Test
        @DisplayName("should_return_false_for_cancellable")
        void shouldReturnFalseForCancellable() {
            assertFalse(item.isCancellable());
        }
    }
}
