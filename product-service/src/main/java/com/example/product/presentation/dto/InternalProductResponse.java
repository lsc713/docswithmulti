package com.example.product.presentation.dto;

import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductVersion;

import java.math.BigDecimal;

public record InternalProductResponse(
        Long productId,
        Long productVersionId,
        String name,
        BigDecimal price,
        BigDecimal discountPrice,
        long merchantId
) {
    public static InternalProductResponse from(Product product, ProductVersion version) {
        return new InternalProductResponse(
                product.getId(),
                version.getId(),
                version.getName(),
                version.getPrice(),
                version.getDiscountPrice(),
                product.getMerchantId()
        );
    }
}
