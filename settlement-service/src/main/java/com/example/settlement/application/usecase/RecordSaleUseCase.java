package com.example.settlement.application.usecase;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 완료 매출 이벤트 1건을 가맹점×정산주 원장에 멱등 적재한다 (RecordCancellationUseCase 동형).
 * Command는 usecase가 소유(application 레이어가 Kafka payload 타입에 의존하지 않도록 — 헥사고날).
 */
public interface RecordSaleUseCase {

    void record(Command command);

    record Command(
        String paymentKey,
        long merchantId,
        BigDecimal grossAmount,   // = totalAmount (반올림 없음)
        Instant occurredAt        // = Instant.parse(completedAt)
    ) {}
}
