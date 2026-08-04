package com.example.settlement.presentation.dto;

import com.example.settlement.domain.entity.Settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 정산 원장 헤더 응답. */
public record SettlementResponse(
    Long id,
    long merchantId,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal grossAmount,
    BigDecimal cancelAmount,
    BigDecimal feeAmount,
    BigDecimal vatAmount,
    BigDecimal netAmount,
    String status,
    Instant finalizedAt
) {
    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(
            s.getId(), s.getMerchantId(), s.getPeriodStart(), s.getPeriodEnd(),
            s.getGrossAmount(), s.getCancelAmount(), s.getFeeAmount(), s.getVatAmount(),
            s.getNetAmount(), s.getStatus(), s.getFinalizedAt());
    }
}
