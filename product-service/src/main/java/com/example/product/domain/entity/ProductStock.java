package com.example.product.domain.entity;

import lombok.Getter;

import java.time.Instant;

/** 순수 POJO (D-P1-2). available_qty 차감은 인프라의 원자 조건부 UPDATE로 수행(오버셀 방지). */
@Getter
public class ProductStock {
    private final Long skuId;
    private final int availableQty;
    private final Instant updatedAt;

    private ProductStock(Long skuId, int availableQty, Instant updatedAt) {
        this.skuId = skuId;
        this.availableQty = availableQty;
        this.updatedAt = updatedAt;
    }

    public static ProductStock create(Long skuId, int initialQty) {
        return new ProductStock(skuId, initialQty, Instant.now());
    }

    public static ProductStock reconstruct(Long skuId, int availableQty, Instant updatedAt) {
        return new ProductStock(skuId, availableQty, updatedAt);
    }
}
