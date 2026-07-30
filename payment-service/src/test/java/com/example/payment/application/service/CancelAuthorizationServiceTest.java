package com.example.payment.application.service;

import com.example.payment.application.authz.AuthenticatedUser;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.common.exception.domain.CancelNotAuthorizedException;
import com.example.payment.domain.entity.Payment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 취소 인가 오케스트레이션 단위 (D-P3-5) — Mockito PaymentRepository mock.
 *
 * ADMIN 로드 생략 / MERCHANT read-only 1회 로드 후 비교 / 404 / 비정상 헤더 403 흡수를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelAuthorizationService 오케스트레이션")
class CancelAuthorizationServiceTest {

    private static final String PAYMENT_KEY = "pay_001";

    @Mock PaymentRepository paymentRepository;
    @InjectMocks CancelAuthorizationService service;

    /** merchantId 만 관심 — 나머지는 픽스처 기본값. */
    private Payment paymentOfMerchant(long merchantId) {
        return Payment.of(PAYMENT_KEY, merchantId, 42L, "TOSS",
            BigDecimal.valueOf(30_000), "KRW", 90);
    }

    @Test
    @DisplayName("(1) ADMIN → payment 로드 생략(never findByPaymentKey) 후 통과")
    void admin_skips_payment_load() {
        AuthenticatedUser admin = new AuthenticatedUser("1", "ADMIN", null);

        assertThatCode(() -> service.authorize(admin, PAYMENT_KEY)).doesNotThrowAnyException();

        verify(paymentRepository, never()).findByPaymentKey(any());
    }

    @Test
    @DisplayName("(2) MERCHANT + 헤더 == payment.merchantId → 1회 로드 후 통과")
    void merchant_match_loads_once_and_passes() {
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(paymentOfMerchant(7L)));
        AuthenticatedUser merchant = new AuthenticatedUser("m1", "MERCHANT", "7");

        assertThatCode(() -> service.authorize(merchant, PAYMENT_KEY)).doesNotThrowAnyException();

        verify(paymentRepository, times(1)).findByPaymentKey(PAYMENT_KEY);
    }

    @Test
    @DisplayName("(3) MERCHANT + 헤더 != payment.merchantId → 403")
    void merchant_mismatch_forbidden() {
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(paymentOfMerchant(7L)));
        AuthenticatedUser merchant = new AuthenticatedUser("m1", "MERCHANT", "8");

        assertForbidden(() -> service.authorize(merchant, PAYMENT_KEY));
    }

    @Test
    @DisplayName("(4) MERCHANT + 대상 payment 없음 → 404 (PaymentNotFound)")
    void merchant_payment_absent_yields_404() {
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.empty());
        AuthenticatedUser merchant = new AuthenticatedUser("m1", "MERCHANT", "7");

        assertThatThrownBy(() -> service.authorize(merchant, PAYMENT_KEY))
            .isInstanceOf(PaymentNotFoundException.class)
            .extracting(e -> ((PaymentNotFoundException) e).getErrorCode())
            .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("(5) USER → 로드 없이 403")
    void user_forbidden_without_load() {
        AuthenticatedUser user = new AuthenticatedUser("42", "USER", null);

        assertForbidden(() -> service.authorize(user, PAYMENT_KEY));

        verify(paymentRepository, never()).findByPaymentKey(any());
    }

    @Test
    @DisplayName("(6) 비정상 X-Merchant-Id(숫자 아님) → 500 대신 403 흡수 (T-03-04)")
    void non_numeric_merchant_id_absorbed_to_403() {
        when(paymentRepository.findByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(paymentOfMerchant(7L)));
        AuthenticatedUser merchant = new AuthenticatedUser("m1", "MERCHANT", "not-a-number");

        // NumberFormatException(→500) 이 아니라 CancelNotAuthorizedException(403)
        assertForbidden(() -> service.authorize(merchant, PAYMENT_KEY));
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(CancelNotAuthorizedException.class)
            .extracting(e -> ((CancelNotAuthorizedException) e).getErrorCode())
            .isEqualTo(ErrorCode.FORBIDDEN_PAYMENT)
            .extracting(ErrorCode::getHttpStatus)
            .isEqualTo(403);
    }
}
