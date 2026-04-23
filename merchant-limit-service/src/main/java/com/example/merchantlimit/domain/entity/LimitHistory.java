package com.example.merchantlimit.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public class LimitHistory {

    private Long id;
    private Long merchantId;
    private BigDecimal oldLimit;   // null = 최초 설정
    private BigDecimal newLimit;
    private String reason;
    private Instant createdAt;

    private LimitHistory(Long merchantId, BigDecimal oldLimit,
                         BigDecimal newLimit, String reason) {
        this.merchantId = merchantId;
        this.oldLimit = oldLimit;
        this.newLimit = newLimit;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public static LimitHistory record(long merchantId, BigDecimal oldLimit,
                                      BigDecimal newLimit, String reason) {
        return new LimitHistory(merchantId, oldLimit, newLimit, reason);
    }

    public Long getId()               { return id; }
    public Long getMerchantId()       { return merchantId; }
    public BigDecimal getOldLimit()   { return oldLimit; }
    public BigDecimal getNewLimit()   { return newLimit; }
    public String getReason()         { return reason; }
    public Instant getCreatedAt()     { return createdAt; }
}
