package com.example.payment.application.usecase;

import com.example.payment.application.authz.AuthenticatedUser;

/**
 * 취소 인가 오케스트레이션 유스케이스.
 *
 * 취소 실행 코어(cancelPaymentUseCase.cancel) 호출 이전에 실행되는 read-only pre-check.
 * 인가 실패 시 CancelNotAuthorizedException 을 던진다 (기존 GlobalExceptionHandler → 403).
 */
public interface CancelAuthorizationUseCase {
    void authorize(AuthenticatedUser user, String paymentKey);
}
