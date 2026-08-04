package com.example.settlement.domain.entity;

import lombok.Getter;

import java.time.Instant;

/**
 * 가맹점 지급 계좌 도메인 POJO — Spring/JPA 어노테이션 없음(CLAUDE.md). MerchantSettlementConfig 와 동일 클래스.
 */
@Getter
public class MerchantPayoutAccount {
    private final long merchantId;
    private final String bankCode;
    private final String accountNumber;
    private final String holderName;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;

    private MerchantPayoutAccount(long merchantId, String bankCode, String accountNumber,
                                  String holderName, boolean active, Instant createdAt, Instant updatedAt) {
        this.merchantId = merchantId;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static MerchantPayoutAccount reconstruct(long merchantId, String bankCode, String accountNumber,
                                                    String holderName, boolean active,
                                                    Instant createdAt, Instant updatedAt) {
        return new MerchantPayoutAccount(merchantId, bankCode, accountNumber, holderName,
            active, createdAt, updatedAt);
    }
}
