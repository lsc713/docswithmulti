package com.example.product.domain.entity;

import lombok.Getter;

import java.time.Instant;

/** 순수 POJO (D-P1-2). */
@Getter
public class ProductSku {
    private final Long id;
    private final Long productId;
    private final String skuCode;
    private final String optionSummary;
    private final Instant createdAt;
    private final Instant updatedAt;

    private ProductSku(Long id, Long productId, String skuCode, String optionSummary,
                       Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.productId = productId;
        this.skuCode = skuCode;
        this.optionSummary = optionSummary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ProductSku create(Long productId, String skuCode, String optionSummary) {
        Instant now = Instant.now();
        return new ProductSku(null, productId, skuCode, optionSummary, now, now);
    }

    public static ProductSku reconstruct(Long id, Long productId, String skuCode, String optionSummary,
                                         Instant createdAt, Instant updatedAt) {
        return new ProductSku(id, productId, skuCode, optionSummary, createdAt, updatedAt);
    }
}
