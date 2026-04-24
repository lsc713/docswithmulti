package com.example.riskmanagement.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MerchantCancelUsage {

    private Long id;
    private Long merchantId;
    private LocalDate kstDate;
    private BigDecimal dailyLimit;
    private BigDecimal usedAmount;

    private MerchantCancelUsage() {}

    public static MerchantCancelUsage create(long merchantId, LocalDate kstDate, BigDecimal dailyLimit) {
        MerchantCancelUsage u = new MerchantCancelUsage();
        u.merchantId = merchantId;
        u.kstDate = kstDate;
        u.dailyLimit = dailyLimit;
        u.usedAmount = BigDecimal.ZERO;
        return u;
    }

    public static MerchantCancelUsage reconstruct(
        Long id, long merchantId, LocalDate kstDate, BigDecimal dailyLimit, BigDecimal usedAmount) {
        MerchantCancelUsage u = new MerchantCancelUsage();
        u.id = id;
        u.merchantId = merchantId;
        u.kstDate = kstDate;
        u.dailyLimit = dailyLimit;
        u.usedAmount = usedAmount;
        return u;
    }

    public void deduct(BigDecimal amount) {
        this.usedAmount = this.usedAmount.add(amount);
    }

    public void restore(BigDecimal amount) {
        this.usedAmount = this.usedAmount.subtract(amount).max(BigDecimal.ZERO);
    }

    public void updateDailyLimit(BigDecimal newLimit) {
        this.dailyLimit = newLimit;
    }

    public BigDecimal remaining() {
        return dailyLimit.subtract(usedAmount);
    }

    public Long getId()               { return id; }
    public Long getMerchantId()       { return merchantId; }
    public LocalDate getKstDate()     { return kstDate; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public BigDecimal getUsedAmount() { return usedAmount; }
}
