package com.example.payment.application.interfaces;

import java.util.List;

/**
 * Kafka Outbox Pattern 관리 인터페이스
 *
 * architecture.md: Outbox Pattern
 * 취소 완료 후 Kafka에 직접 발행하지 않고,
 * cancel_event_outbox 테이블에 INSERT → 스케줄러가 발행
 *
 * infrastructure/persistence에서 DB 기반 구현
 */
public interface CancelEventOutboxManager {

    /**
     * 취소 이벤트를 Outbox에 기록
     *
     * @param cancelRequestId 취소 요청 ID
     * @param paymentKey Payment key
     * @param cancelAmount 취소 금액
     * @param cancelledItemIds 취소된 항목 ID 목록
     */
    void recordCancelEvent(
        Long cancelRequestId,
        String paymentKey,
        java.math.BigDecimal cancelAmount,
        List<Long> cancelledItemIds
    );
}
