package com.example.payment.application.interfaces;

import com.example.payment.application.dto.PgCancelResult;
import java.math.BigDecimal;

public interface PgCancelPort {

    /** PG사 취소 실행 */
    PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason);

    /**
     * PG사 취소 건 상태 조회.
     * 조회 실패(네트워크 오류 등) 시 예외 throw → 스케줄러가 PROCESSING 유지.
     */
    PgCancelResult getStatus(String paymentKey);
}
