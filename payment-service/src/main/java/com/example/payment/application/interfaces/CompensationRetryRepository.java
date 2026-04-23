package com.example.payment.application.interfaces;

import java.math.BigDecimal;

public interface CompensationRetryRepository {
    void save(long cancelRequestId, long merchantId, BigDecimal restoreAmount);
}
