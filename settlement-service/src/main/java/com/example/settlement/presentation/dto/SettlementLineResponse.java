package com.example.settlement.presentation.dto;

import com.example.settlement.domain.entity.SettlementLine;

import java.math.BigDecimal;
import java.time.Instant;

/** 정산 원장 라인 응답. */
public record SettlementLineResponse(
    Long id,
    String type,
    String paymentKey,
    BigDecimal amount,
    String eventId,
    Instant occurredAt
) {
    public static SettlementLineResponse from(SettlementLine l) {
        return new SettlementLineResponse(
            l.getId(), l.getType(), l.getPaymentKey(), l.getAmount(), l.getEventId(), l.getOccurredAt());
    }
}
