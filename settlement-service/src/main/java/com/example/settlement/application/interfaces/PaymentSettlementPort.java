package com.example.settlement.application.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * payment-service 정산 조회 포트 (RECON-01/03 계약). application 이 계약을 소유(헥사고날 —
 * HTTP/wire 타입에 application 레이어가 의존하지 않도록). 구현: PaymentSettlementHttpClient.
 *
 * <p>fetch 실패는 예외를 그대로 전파한다 — 빈 리스트로 강등해 삼키면 정상 back-fill 을 누락(under-reconcile)한다.
 */
public interface PaymentSettlementPort {

    /** [from, to) 기간의 가맹점 완료 결제 + 취소를 조회. */
    List<PaymentView> fetch(long merchantId, Instant from, Instant to);

    /** 완료 결제 1건 뷰. createdAt = 매출 귀속 시각. */
    record PaymentView(
        String paymentKey,
        long merchantId,
        BigDecimal totalAmount,
        String status,
        Instant createdAt,
        List<CancelView> cancels
    ) {}

    /** 취소 1건 뷰. completedAt = 취소 귀속 시각(정산주 버킷 기준). */
    record CancelView(
        String cancelRequestId,
        BigDecimal cancelAmount,
        Instant completedAt
    ) {}
}
