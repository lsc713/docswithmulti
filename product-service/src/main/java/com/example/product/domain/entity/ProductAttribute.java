package com.example.product.domain.entity;

import lombok.Getter;

/** 상품이 선언한 속성 + 역할(변형/서술). is_variant=true → SKU 정의, false → 서술 태그. (순수 POJO). */
@Getter
public class ProductAttribute {
    private final Long productId;
    private final Long attributeId;
    private final boolean variant;

    private ProductAttribute(Long productId, Long attributeId, boolean variant) {
        this.productId = productId;
        this.attributeId = attributeId;
        this.variant = variant;
    }

    public static ProductAttribute create(Long productId, Long attributeId, boolean variant) {
        return new ProductAttribute(productId, attributeId, variant);
    }

    public static ProductAttribute reconstruct(Long productId, Long attributeId, boolean variant) {
        return new ProductAttribute(productId, attributeId, variant);
    }
}
