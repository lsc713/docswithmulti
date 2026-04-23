package com.example.merchantlimit.fixture;

import com.example.merchantlimit.domain.entity.MerchantCancelLimit;

import java.math.BigDecimal;

public class MerchantCancelLimitFixture {

    public static MerchantCancelLimit of(long merchantId, long dailyLimit) {
        return MerchantCancelLimit.reconstruct(1L, merchantId, BigDecimal.valueOf(dailyLimit));
    }

    public static MerchantCancelLimit defaultLimit(long merchantId) {
        return of(merchantId, 5_000_000L);
    }
}
