package com.example.payment.application.usecase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 정산 대사(reconcile) 전용 read-only 조회 (RECON-03).
 *
 * 한 가맹점의 KST 정산 주간 윈도우 [from,to)에 대해:
 *   - SALE 윈도우: payment.created_at ∈ [from,to) 인 비-PENDING 결제
 *   - CANCEL 윈도우: cancel_request.completed_at ∈ [from,to) 인 COMPLETED 취소 (부모 결제 주간과 독립)
 * 두 윈도우를 paymentKey로 조립한 중첩 뷰를 반환한다. 부모 결제가 이전 주(W-1)에 생성됐어도
 * 이번 주(W)에 완료된 취소가 있으면 "carrier"로 등장한다 (decision D-Q1).
 *
 * 순수 read 경로 — 취소 코어/결제 생성 로직과 무관, 어떤 write도 하지 않는다.
 */
public interface PaymentSettlementQuery {

    List<PaymentSettlementView> query(long merchantId, Instant from, Instant to);

    /** 결제 1건 + 이번 윈도우에 완료된 취소 목록 (application-layer projection, web 타입 없음). */
    record PaymentSettlementView(
        String paymentKey,
        long merchantId,
        BigDecimal totalAmount,
        String status,
        Instant createdAt,
        List<CancelView> cancels
    ) {}

    record CancelView(long cancelRequestId, BigDecimal cancelAmount, Instant completedAt) {}
}
