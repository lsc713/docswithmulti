package com.example.merchantlimit.presentation.dto;

import com.example.merchantlimit.domain.entity.Merchant;

public record CreateMerchantV1Response(
    long merchantId,
    String merchantKey,
    String name
) {
    public static CreateMerchantV1Response from(Merchant merchant) {
        return new CreateMerchantV1Response(
            merchant.getId(),
            merchant.getMerchantKey(),
            merchant.getName()
        );
    }
}
