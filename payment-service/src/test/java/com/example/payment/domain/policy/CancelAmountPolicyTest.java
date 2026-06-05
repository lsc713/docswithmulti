package com.example.payment.domain.policy;

import com.example.payment.common.exception.domain.InvalidCancelAmountException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelAmountPolicy 테스트")
class CancelAmountPolicyTest {

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
    @DisplayName("cancel_amount가 큰 금액이면 검증 통과")
    void should_validate_cancel_amount_when_amount_is_large() {
        assertDoesNotThrow(() -> CancelAmountPolicy.validateCancelAmount(BigDecimal.valueOf(100000)));
    }
}
