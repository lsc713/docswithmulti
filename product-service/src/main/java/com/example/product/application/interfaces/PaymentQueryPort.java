package com.example.product.application.interfaces;

/**
 * payment-service 존재확인 조회 포트 (RST-03, D-P3-3).
 *
 * <p>orphan 예약 복구가 커밋된 payment 존재 여부를 확인하는 유일 경로.
 * best-effort 배경 조회 — 조회 실패(장애/타임아웃)는 예외로 전파해 호출측이 skip한다(fail-safe:
 * 조회 불가를 "미존재"로 오인해 release하면 재고 조기 복원 오류. 예외 시 절대 release 금지).
 */
public interface PaymentQueryPort {

    /**
     * @param paymentKey 조회 대상 결제키
     * @return true = 커밋된 payment 존재, false = 미존재(orphan 후보)
     * @throws RuntimeException 조회 실패 — 호출측은 해당 건 skip(다음 주기 재시도)
     */
    boolean exists(String paymentKey);
}
