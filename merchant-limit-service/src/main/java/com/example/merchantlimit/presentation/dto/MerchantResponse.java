package com.example.merchantlimit.presentation.dto;

import com.example.merchantlimit.domain.entity.Merchant;

public record MerchantResponse(
    long merchantId, String merchantKey, String name,
    String status, int cancelPeriodDays
) {
    public static MerchantResponse from(Merchant m) {
        return new MerchantResponse(
            m.getId(), m.getMerchantKey(), m.getName(),
            m.getStatus().name(), m.getCancelPeriodDays()
        );
    }
}
