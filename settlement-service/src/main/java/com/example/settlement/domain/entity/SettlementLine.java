package com.example.settlement.domain.entity;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** 정산 원장 라인(감사추적 + 멱등 단위). 순수 도메인 POJO. */
@Getter
public class SettlementLine {
    private final Long id;
    private final long settlementId;
    private final String type;        // SALE | CANCEL
    private final String paymentKey;
    private final BigDecimal amount;
    private final String eventId;
    private final Instant occurredAt;
    private final Instant createdAt;

    private SettlementLine(Long id, long settlementId, String type, String paymentKey,
                           BigDecimal amount, String eventId, Instant occurredAt, Instant createdAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.type = type;
        this.paymentKey = paymentKey;
        this.amount = amount;
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public static SettlementLine reconstruct(Long id, long settlementId, String type, String paymentKey,
                                             BigDecimal amount, String eventId, Instant occurredAt, Instant createdAt) {
        return new SettlementLine(id, settlementId, type, paymentKey, amount, eventId, occurredAt, createdAt);
    }
}
