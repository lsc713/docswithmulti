package com.example.merchantlimit.domain.service;

import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;

import java.math.BigDecimal;

public class MerchantLimitDomainService {

    /**
     * 가맹점 상태 검증 후 한도 변경.
     * SUSPENDED → MerchantSuspendedException
     * 한도 0원 이하 → InvalidLimitAmountException
     */
    public void updateLimit(Merchant merchant, MerchantCancelLimit limit, BigDecimal newLimit) {
        merchant.validateLimitChangeable();
        limit.update(newLimit);
    }
}
