package com.example.product.presentation.dto;

import com.example.product.domain.entity.ProductVersion;

import java.math.BigDecimal;

public record VersionResponse(
        Long id,
        String name,
        BigDecimal price,
        BigDecimal discountPrice,
        String attributes,
        int version,
        boolean isCurrent
) {
    public static VersionResponse from(ProductVersion v) {
        return new VersionResponse(
                v.getId(),
                v.getName(),
                v.getPrice(),
                v.getDiscountPrice(),
                v.getAttributes(),
                v.getVersion(),
                v.isCurrent()
        );
    }
}
