package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.InvalidLimitAmountException;

import java.math.BigDecimal;

public class MerchantCancelLimit {

    private Long id;
    private Long merchantId;
    private BigDecimal dailyLimit;

    private MerchantCancelLimit(Long merchantId, BigDecimal dailyLimit) {
        validate(dailyLimit);
        this.merchantId = merchantId;
        this.dailyLimit = dailyLimit;
    }

    public static MerchantCancelLimit create(long merchantId, BigDecimal dailyLimit) {
        return new MerchantCancelLimit(merchantId, dailyLimit);
    }

    public static MerchantCancelLimit reconstruct(long id, long merchantId, BigDecimal dailyLimit) {
        MerchantCancelLimit l = new MerchantCancelLimit(merchantId, dailyLimit);
        l.id = id;
        return l;
    }

    public void update(BigDecimal newLimit) {
        validate(newLimit);
        this.dailyLimit = newLimit;
    }

    private static void validate(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ONE) < 0) {
            throw new InvalidLimitAmountException(amount);
        }
    }

    public Long getId()               { return id; }
    public Long getMerchantId()       { return merchantId; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
}
