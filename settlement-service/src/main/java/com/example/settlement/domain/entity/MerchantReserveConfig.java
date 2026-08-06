package com.example.settlement.domain.entity;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 가맹점 유보 정책 도메인 POJO — Spring/JPA 어노테이션 없음(CLAUDE.md). MerchantSettlementConfig 미러.
 * reserveRate(net 대비 유보율), reserveCap(누적 유보 상한), holdDays(유보→릴리스 hold 기간).
 */
@Getter
public class MerchantReserveConfig {
    private final long merchantId;
    private final BigDecimal reserveRate;
    private final BigDecimal reserveCap;
    private final int holdDays;

    private MerchantReserveConfig(long merchantId, BigDecimal reserveRate, BigDecimal reserveCap, int holdDays) {
        this.merchantId = merchantId;
        this.reserveRate = reserveRate;
        this.reserveCap = reserveCap;
        this.holdDays = holdDays;
    }

    public static MerchantReserveConfig reconstruct(long merchantId, BigDecimal reserveRate,
                                                    BigDecimal reserveCap, int holdDays) {
        return new MerchantReserveConfig(merchantId, reserveRate, reserveCap, holdDays);
    }
}
