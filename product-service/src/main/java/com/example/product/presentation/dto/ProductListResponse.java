package com.example.product.presentation.dto;

import com.example.product.domain.entity.Product;

public record ProductListResponse(
        Long id,
        long merchantId,
        long categoryId,
        String status
) {
    public static ProductListResponse from(Product product) {
        return new ProductListResponse(
                product.getId(),
                product.getMerchantId(),
                product.getCategoryId(),
                product.getStatus().name()
        );
    }
}
