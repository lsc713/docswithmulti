package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.MerchantSuspendedException;

public class Merchant {

    private Long id;
    private String merchantKey;
    private String name;
    private MerchantStatus status;
    private int cancelPeriodDays;

    private Merchant(String merchantKey, String name, int cancelPeriodDays) {
        this.merchantKey = merchantKey;
        this.name = name;
        this.status = MerchantStatus.ACTIVE;
        this.cancelPeriodDays = cancelPeriodDays;
    }

    public static Merchant create(String merchantKey, String name, int cancelPeriodDays) {
        return new Merchant(merchantKey, name, cancelPeriodDays);
    }

    public static Merchant reconstruct(Long id, String merchantKey, String name,
                                       MerchantStatus status, int cancelPeriodDays) {
        Merchant m = new Merchant(merchantKey, name, cancelPeriodDays);
        m.id = id;
        m.status = status;
        return m;
    }

    public void activate()   { this.status = MerchantStatus.ACTIVE; }
    public void deactivate() { this.status = MerchantStatus.INACTIVE; }
    public void suspend()    { this.status = MerchantStatus.SUSPENDED; }

    public void validateLimitChangeable() {
        if (this.status == MerchantStatus.SUSPENDED) {
            throw new MerchantSuspendedException(id != null ? id : 0L);
        }
    }

    public Long getId()               { return id; }
    public String getMerchantKey()    { return merchantKey; }
    public String getName()           { return name; }
    public MerchantStatus getStatus() { return status; }
    public int getCancelPeriodDays()  { return cancelPeriodDays; }
}
