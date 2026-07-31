package com.example.payment.application.usecase;

/**
 * paymentKey로 커밋된 Payment 존재 여부 조회 (RST-03 orphan 복구 전제).
 *
 * 순수 read 경로 — 취소 코어/결제 생성 로직과 무관.
 */
public interface PaymentExistsQuery {
    boolean exists(String paymentKey);
}
