package com.example.merchantlimit.presentation.dto;

import com.example.merchantlimit.domain.entity.Merchant;
import org.springframework.data.domain.Page;
import java.util.List;

public record MerchantPageResponse(
    List<MerchantResponse> content, long totalElements, int page, int size
) {
    public static MerchantPageResponse from(Page<Merchant> page) {
        return new MerchantPageResponse(
            page.getContent().stream().map(MerchantResponse::from).toList(),
            page.getTotalElements(), page.getNumber(), page.getSize()
        );
    }
}
