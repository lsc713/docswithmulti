package com.example.settlement.domain.entity;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 정산 원장 헤더(가맹점 × 정산주). 순수 도메인 POJO — Spring/JPA 어노테이션 없음(CLAUDE.md).
 * 적재는 native upsert/increment로 이뤄지므로 이 엔티티는 조회 매핑 중심(read-mostly).
 */
@Getter
public class Settlement {
    private final Long id;
    private final long merchantId;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final BigDecimal grossAmount;
    private final BigDecimal cancelAmount;
    private final BigDecimal feeAmount;
    private final BigDecimal vatAmount;
    private final BigDecimal netAmount;
    private final String status;
    private final Instant finalizedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Settlement(Long id, long merchantId, LocalDate periodStart, LocalDate periodEnd,
                       BigDecimal grossAmount, BigDecimal cancelAmount, BigDecimal feeAmount,
                       BigDecimal vatAmount, BigDecimal netAmount, String status,
                       Instant finalizedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.grossAmount = grossAmount;
        this.cancelAmount = cancelAmount;
        this.feeAmount = feeAmount;
        this.vatAmount = vatAmount;
        this.netAmount = netAmount;
        this.status = status;
        this.finalizedAt = finalizedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Settlement reconstruct(Long id, long merchantId, LocalDate periodStart, LocalDate periodEnd,
                                         BigDecimal grossAmount, BigDecimal cancelAmount, BigDecimal feeAmount,
                                         BigDecimal vatAmount, BigDecimal netAmount, String status,
                                         Instant finalizedAt, Instant createdAt, Instant updatedAt) {
        return new Settlement(id, merchantId, periodStart, periodEnd, grossAmount, cancelAmount,
            feeAmount, vatAmount, netAmount, status, finalizedAt, createdAt, updatedAt);
    }
}
