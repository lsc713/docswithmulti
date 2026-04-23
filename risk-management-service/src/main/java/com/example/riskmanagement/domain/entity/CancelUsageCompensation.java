package com.example.riskmanagement.domain.entity;

import java.math.BigDecimal;

public class CancelUsageCompensation {

    private Long id;
    private String cancelRequestId;
    private Long merchantId;
    private BigDecimal restoreAmount;

    private CancelUsageCompensation() {}

    public static CancelUsageCompensation record(
        String cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        CancelUsageCompensation c = new CancelUsageCompensation();
        c.cancelRequestId = cancelRequestId;
        c.merchantId = merchantId;
        c.restoreAmount = restoreAmount;
        return c;
    }

    public static CancelUsageCompensation reconstruct(
        Long id, String cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        CancelUsageCompensation c = new CancelUsageCompensation();
        c.id = id;
        c.cancelRequestId = cancelRequestId;
        c.merchantId = merchantId;
        c.restoreAmount = restoreAmount;
        return c;
    }

    public Long getId()                   { return id; }
    public String getCancelRequestId()    { return cancelRequestId; }
    public Long getMerchantId()           { return merchantId; }
    public BigDecimal getRestoreAmount()  { return restoreAmount; }
}
