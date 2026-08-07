package com.example.settlement.domain.entity;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 유보금 도메인 POJO — Spring/JPA 어노테이션 없음(CLAUDE.md). Payout 미러.
 * status 는 문자열로 보유(HELD/RELEASING/...). 적재는 native INSERT + status-guarded UPDATE(Phase 2)라 조회 매핑 중심.
 */
@Getter
public class Reserve {
    private final Long id;
    private final long settlementId;
    private final long merchantId;
    private final BigDecimal amount;
    private final String status;
    private final LocalDate holdUntil;
    private final String transferRef;
    private final int attemptCount;
    private final String lastError;
    private final Instant heldAt;
    private final Instant releasedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Reserve(Long id, long settlementId, long merchantId, BigDecimal amount, String status,
                    LocalDate holdUntil, String transferRef, int attemptCount, String lastError,
                    Instant heldAt, Instant releasedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.status = status;
        this.holdUntil = holdUntil;
        this.transferRef = transferRef;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.heldAt = heldAt;
        this.releasedAt = releasedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Reserve reconstruct(Long id, long settlementId, long merchantId, BigDecimal amount,
                                      String status, LocalDate holdUntil, String transferRef, int attemptCount,
                                      String lastError, Instant heldAt, Instant releasedAt,
                                      Instant createdAt, Instant updatedAt) {
        return new Reserve(id, settlementId, merchantId, amount, status, holdUntil, transferRef,
            attemptCount, lastError, heldAt, releasedAt, createdAt, updatedAt);
    }
}
