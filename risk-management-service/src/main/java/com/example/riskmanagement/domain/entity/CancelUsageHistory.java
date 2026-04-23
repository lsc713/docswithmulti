package com.example.riskmanagement.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CancelUsageHistory {

    private Long id;
    private String cancelRequestId;
    private Long merchantId;
    private LocalDate kstDate;
    private BigDecimal cancelAmount;

    private CancelUsageHistory() {}

    public static CancelUsageHistory record(
        String cancelRequestId, long merchantId, LocalDate kstDate, BigDecimal cancelAmount) {
        CancelUsageHistory h = new CancelUsageHistory();
        h.cancelRequestId = cancelRequestId;
        h.merchantId = merchantId;
        h.kstDate = kstDate;
        h.cancelAmount = cancelAmount;
        return h;
    }

    public static CancelUsageHistory reconstruct(
        Long id, String cancelRequestId, Long merchantId, LocalDate kstDate, BigDecimal cancelAmount) {
        CancelUsageHistory h = new CancelUsageHistory();
        h.id = id;
        h.cancelRequestId = cancelRequestId;
        h.merchantId = merchantId;
        h.kstDate = kstDate;
        h.cancelAmount = cancelAmount;
        return h;
    }

    public Long getId()                   { return id; }
    public String getCancelRequestId()    { return cancelRequestId; }
    public Long getMerchantId()           { return merchantId; }
    public LocalDate getKstDate()         { return kstDate; }
    public BigDecimal getCancelAmount()   { return cancelAmount; }
}
