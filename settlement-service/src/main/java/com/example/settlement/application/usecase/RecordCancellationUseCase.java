package com.example.settlement.application.usecase;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 취소 이벤트 1건을 가맹점×정산주 원장에 멱등 적재한다.
 * Command는 usecase가 소유(인프라의 Kafka payload 타입에 application 레이어가 의존하지 않도록 — 헥사고날).
 */
public interface RecordCancellationUseCase {

    void record(Command command);

    record Command(
        String cancelRequestId,
        String paymentKey,
        long merchantId,
        BigDecimal cancelAmount,   // Σ cancelledItems[].itemAmount (반올림 없음 — raw 합)
        Instant occurredAt         // = Instant.parse(cancelledAt)
    ) {}
}
