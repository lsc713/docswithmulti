package com.example.payment.domain.policy;

import com.example.payment.domain.entity.Payment;
import com.example.payment.common.exception.domain.CancelPeriodExceededException;
import com.example.payment.fixture.PaymentFixture;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelPeriodPolicy 테스트")
class CancelPeriodPolicyTest {

    // 기준 시각: 2026-04-01 00:00:00 UTC
    private static final LocalDateTime REFERENCE_TIME = LocalDateTime.of(2026, 4, 1, 0, 0, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        REFERENCE_TIME.toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );

    @Test
    @DisplayName("취소 기간 내에는 취소 가능")
    void should_validate_when_cancel_period_not_exceeded() {
        // Payment의 createdAt = 2026-01-01, 취소 기간 = 90일
        // 만료일 = 2026-03-31 23:59:59
        // 현재 = 2026-03-31 23:59:00 → 기간 내
        Payment payment = PaymentFixture.completedPayment();
        CancelPeriodPolicy policy = new CancelPeriodPolicy(
            Clock.fixed(
                LocalDateTime.of(2026, 3, 31, 23, 59, 0).toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC
            )
        );

        assertDoesNotThrow(
            () -> policy.validateCancelPeriod(payment)
        );
    }

    @Test
    @DisplayName("취소 기간 초과시 CancelPeriodExceededException 발생")
    void should_reject_when_cancel_period_exceeded() {
        // Payment의 createdAt = 2026-01-01, 취소 기간 = 90일
        // 만료일 = 2026-04-01 00:00:00
        // 현재 = 2026-04-01 00:00:01 → 기간 초과
        Payment payment = PaymentFixture.completedPayment();
        Clock expiredClock = Clock.fixed(
            LocalDateTime.of(2026, 4, 1, 0, 0, 1).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC
        );
        CancelPeriodPolicy policy = new CancelPeriodPolicy(expiredClock);

        CancelPeriodExceededException exception = assertThrows(
            CancelPeriodExceededException.class,
            () -> policy.validateCancelPeriod(payment)
        );
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0, 0), exception.getCreatedAt());
        assertEquals(90, exception.getCancelPeriodDays());
    }

    @Test
    @DisplayName("취소 기간이 1일인 경우 기간 초과 거부")
    void should_reject_when_1day_period_exceeded() {
        // Payment의 createdAt = 2026-01-01, 취소 기간 = 1일
        // 만료일 = 2026-01-02 00:00:00
        // 현재 = 2026-01-02 00:00:01 → 기간 초과
        Payment payment = PaymentFixture.completedPaymentWith1DayPeriod();
        Clock expiredClock = Clock.fixed(
            LocalDateTime.of(2026, 1, 2, 0, 0, 1).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC
        );
        CancelPeriodPolicy policy = new CancelPeriodPolicy(expiredClock);

        assertThrows(
            CancelPeriodExceededException.class,
            () -> policy.validateCancelPeriod(payment)
        );
    }

    @Test
    @DisplayName("취소 기간이 1일인 경우 기간 내에는 통과")
    void should_validate_when_1day_period_not_exceeded() {
        // Payment의 createdAt = 2026-01-01, 취소 기간 = 1일
        // 만료일 = 2026-01-02 00:00:00
        // 현재 = 2026-01-01 23:59:59 → 기간 내
        Payment payment = PaymentFixture.completedPaymentWith1DayPeriod();
        Clock withinClock = Clock.fixed(
            LocalDateTime.of(2026, 1, 1, 23, 59, 59).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC
        );
        CancelPeriodPolicy policy = new CancelPeriodPolicy(withinClock);

        assertDoesNotThrow(
            () -> policy.validateCancelPeriod(payment)
        );
    }

    @Test
    @DisplayName("취소 기간 만료 정확한 순간 통과 (isAfter 경계)")
    void should_validate_at_exact_expiration_time() {
        // Payment의 createdAt = 2026-01-01, 취소 기간 = 90일
        // 만료일 = 2026-04-01 00:00:00
        // 현재 = 2026-04-01 00:00:00 → isAfter는 false (equal은 통과)
        Payment payment = PaymentFixture.completedPayment();
        CancelPeriodPolicy policy = new CancelPeriodPolicy(FIXED_CLOCK);

        assertDoesNotThrow(
            () -> policy.validateCancelPeriod(payment)
        );
    }

    @Test
    @DisplayName("취소 기간 초과 직후 거부 (isAfter 경계)")
    void should_reject_just_after_expiration() {
        // Payment의 createdAt = 2026-01-01, 취소 기간 = 90일
        // 만료일 = 2026-04-01 00:00:00
        // 현재 = 2026-04-01 00:00:01 → isAfter는 true
        Payment payment = PaymentFixture.completedPayment();
        Clock justAfterClock = Clock.fixed(
            LocalDateTime.of(2026, 4, 1, 0, 0, 1).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC
        );
        CancelPeriodPolicy policy = new CancelPeriodPolicy(justAfterClock);

        assertThrows(
            CancelPeriodExceededException.class,
            () -> policy.validateCancelPeriod(payment)
        );
    }
}
