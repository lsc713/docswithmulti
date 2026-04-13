package com.example.payment.domain.policy;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.exception.CancelAmountExceededException;
import com.example.payment.domain.exception.InvalidCancelAmountException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelAmountPolicy 테스트")
class CancelAmountPolicyTest {

    private PaymentItem item;

    @BeforeEach
    void setUp() {
        item = PaymentItem.of(
            1L, 1L, 100L, 100L, "상품A", BigDecimal.valueOf(10000)
        );
    }

    @Test
    @DisplayName("cancel_amount가 1원 이상이면 검증 통과")
    void should_validate_cancel_amount_when_amount_is_one_won() {
        assertDoesNotThrow(() -> CancelAmountPolicy.validateCancelAmount(BigDecimal.ONE));
    }

    @Test
    @DisplayName("cancel_amount가 0원이면 InvalidCancelAmountException 발생")
    void should_reject_cancel_amount_when_amount_is_zero() {
        BigDecimal cancelAmount = BigDecimal.ZERO;

        InvalidCancelAmountException exception = assertThrows(
            InvalidCancelAmountException.class,
            () -> CancelAmountPolicy.validateCancelAmount(cancelAmount)
        );
        assertEquals(cancelAmount, exception.getCancelAmount());
    }

    @Test
    @DisplayName("cancel_amount가 음수이면 InvalidCancelAmountException 발생")
    void should_reject_cancel_amount_when_amount_is_negative() {
        BigDecimal cancelAmount = BigDecimal.valueOf(-100);

        InvalidCancelAmountException exception = assertThrows(
            InvalidCancelAmountException.class,
            () -> CancelAmountPolicy.validateCancelAmount(cancelAmount)
        );
        assertEquals(cancelAmount, exception.getCancelAmount());
    }

    @Test
    @DisplayName("cancel_amount가 1000원 이상이면 검증 통과")
    void should_validate_cancel_amount_when_amount_is_large() {
        assertDoesNotThrow(() -> CancelAmountPolicy.validateCancelAmount(BigDecimal.valueOf(100000)));
    }

    @Test
    @DisplayName("항목의 취소액이 잔액을 초과하면 CancelAmountExceededException 발생")
    void should_reject_cancel_amount_when_exceeds_available_amount() {
        BigDecimal cancelAmount = BigDecimal.valueOf(15000);

        CancelAmountExceededException exception = assertThrows(
            CancelAmountExceededException.class,
            () -> CancelAmountPolicy.validateItemCancelAmount(item, cancelAmount)
        );
        assertEquals(item.getOrderItemId(), exception.getPaymentItemId());
        assertEquals(cancelAmount, exception.getRequestedAmount());
    }

    @Test
    @DisplayName("항목의 취소액이 잔액 이내이면 검증 통과")
    void should_validate_cancel_amount_when_within_available_amount() {
        BigDecimal cancelAmount = BigDecimal.valueOf(5000);

        assertDoesNotThrow(
            () -> CancelAmountPolicy.validateItemCancelAmount(item, cancelAmount)
        );
    }

    @Test
    @DisplayName("항목의 취소액이 정확히 잔액과 같으면 검증 통과")
    void should_validate_cancel_amount_when_equals_available_amount() {
        BigDecimal cancelAmount = BigDecimal.valueOf(10000);

        assertDoesNotThrow(
            () -> CancelAmountPolicy.validateItemCancelAmount(item, cancelAmount)
        );
    }

    @Test
    @DisplayName("부분취소된 항목의 잔액을 고려해 검증")
    void should_validate_cancel_amount_considering_previously_cancelled_amount() {
        item.cancelPartially(BigDecimal.valueOf(3000));
        BigDecimal cancelAmount = BigDecimal.valueOf(7000);

        assertDoesNotThrow(
            () -> CancelAmountPolicy.validateItemCancelAmount(item, cancelAmount)
        );
    }

    @Test
    @DisplayName("부분취소된 항목의 잔액 초과시 예외 발생")
    void should_reject_cancel_amount_exceeding_partially_cancelled_item() {
        item.cancelPartially(BigDecimal.valueOf(3000));
        BigDecimal cancelAmount = BigDecimal.valueOf(8000);

        assertThrows(
            CancelAmountExceededException.class,
            () -> CancelAmountPolicy.validateItemCancelAmount(item, cancelAmount)
        );
    }
}
