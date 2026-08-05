package com.example.settlement.domain.entity;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 지급 건 도메인 POJO — Spring/JPA 어노테이션 없음(CLAUDE.md). status 는 문자열로 보유(PROCESSING/PAID/FAILED).
 * 적재는 JPA save(INSERT) + status-guarded native UPDATE 로 이뤄지므로 조회 매핑 중심(read-mostly).
 */
@Getter
public class Payout {
    private final Long id;
    private final long settlementId;
    private final long merchantId;
    private final BigDecimal amount;
    private final String status;
    private final String transferRef;
    private final int attemptCount;
    private final String lastError;
    private final Instant requestedAt;
    private final Instant paidAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Payout(Long id, long settlementId, long merchantId, BigDecimal amount, String status,
                   String transferRef, int attemptCount, String lastError, Instant requestedAt,
                   Instant paidAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.status = status;
        this.transferRef = transferRef;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.requestedAt = requestedAt;
        this.paidAt = paidAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Payout reconstruct(Long id, long settlementId, long merchantId, BigDecimal amount,
                                     String status, String transferRef, int attemptCount, String lastError,
                                     Instant requestedAt, Instant paidAt, Instant createdAt, Instant updatedAt) {
        return new Payout(id, settlementId, merchantId, amount, status, transferRef, attemptCount,
            lastError, requestedAt, paidAt, createdAt, updatedAt);
    }
}
