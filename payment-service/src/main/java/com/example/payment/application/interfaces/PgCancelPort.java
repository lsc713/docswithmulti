package com.example.payment.application.interfaces;

import com.example.payment.application.dto.PgCancelResult;
import java.math.BigDecimal;

public interface PgCancelPort {

    /** PG사 취소 실행 */
    PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason);

    /**
     * PG사 취소 건 상태 조회.
     * cancelAmount: Toss Payment.cancels[] 중 우리 취소 건을 금액으로 매칭하기 위해 필요
     * (D-01: 실 Toss 계약에는 취소 건 단위 상태 조회 엔드포인트가 없음 —
     *  결제 전체 조회 응답의 cancels[] 배열에서 매칭).
     * 조회 실패(네트워크 오류 등) 시 예외 throw → 스케줄러가 PROCESSING 유지.
     */
    PgCancelResult getStatus(String paymentKey, BigDecimal cancelAmount);
}
