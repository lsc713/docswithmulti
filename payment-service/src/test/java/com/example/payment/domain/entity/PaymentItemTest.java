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
    @DisplayName("equals / hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("동일 객체는 equal이다")
        void sameInstance_isEqual() {
            assertEquals(item, item);
        }

        @Test
        @DisplayName("null과는 equal이 아니다")
        void nullIsNotEqual() {
            assertNotEquals(null, item);
        }

        @Test
        @DisplayName("다른 클래스와는 equal이 아니다")
        void differentClassIsNotEqual() {
            assertNotEquals("string", item);
        }

        @Test
        @DisplayName("같은 id·paymentId이면 equal이다")
        void sameIdAndPaymentId_isEqual() {
            PaymentItem same = PaymentItem.reconstruct(0L, 1L, 10L, 100L, 200L, "상품B",
                BigDecimal.valueOf(99999), PaymentItemStatus.CANCELLED);
            assertEquals(item, same);
            assertEquals(item.hashCode(), same.hashCode());
        }

        @Test
        @DisplayName("id가 다르면 equal이 아니다")
        void differentId_isNotEqual() {
            PaymentItem other = PaymentItem.reconstruct(99L, 1L, 10L, 100L, 200L, "상품A",
                BigDecimal.valueOf(30000), PaymentItemStatus.ACTIVE);
            assertNotEquals(item, other);
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
