package com.example.product.domain.entity;

import lombok.Getter;

import java.time.Instant;

/** 순수 POJO — product 당 여러 이미지, sort_order 로 노출 순서 관리. */
@Getter
public class ProductImage {
    private final Long id;
    private final Long productId;
    private final String s3Key;
    private final int sortOrder;
    private final Instant createdAt;

    private ProductImage(Long id, Long productId, String s3Key, int sortOrder, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.s3Key = s3Key;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    public static ProductImage create(Long productId, String s3Key, int sortOrder) {
        return new ProductImage(null, productId, s3Key, sortOrder, Instant.now());
    }

    public static ProductImage reconstruct(Long id, Long productId, String s3Key, int sortOrder, Instant createdAt) {
        return new ProductImage(id, productId, s3Key, sortOrder, createdAt);
    }
}
