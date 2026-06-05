package com.example.payment.domain.policy;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.common.exception.domain.InvalidPaymentItemStatusException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentItemStatusPolicy 테스트")
class PaymentItemStatusPolicyTest {

    private static final BigDecimal ITEM_AMOUNT = BigDecimal.valueOf(10000);

    @Nested
    @DisplayName("ACTIVE 상태일 때")
    class WhenActive {

        @Test
        @DisplayName("취소 가능 상태이므로 검증 통과")
        void should_validate_when_status_is_active() {
            PaymentItem item = PaymentItem.of(1L, 100L, 10L, 1000L, 0L, 1, "상품명", ITEM_AMOUNT);

            assertDoesNotThrow(() -> PaymentItemStatusPolicy.validateCancellableStatus(item));
        }
    }

    @Nested
    @DisplayName("CANCELLED 상태일 때")
    class WhenCancelled {

        @Test
        @DisplayName("취소 불가능 상태이므로 InvalidPaymentItemStatusException 발생")
        void should_reject_when_status_is_cancelled() {
            PaymentItem item = PaymentItem.of(1L, 100L, 10L, 1000L, 0L, 1, "상품명", ITEM_AMOUNT);
            item.cancel();

            InvalidPaymentItemStatusException exception = assertThrows(
                InvalidPaymentItemStatusException.class,
                () -> PaymentItemStatusPolicy.validateCancellableStatus(item)
            );
            assertEquals(PaymentItemStatus.CANCELLED, exception.getCurrentStatus());
        }
    }
}
