package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSkuTest {

    @Test
    @DisplayName("of()로 SKU 생성 — skuCode 형식: SKU-{productId}-{color}-{size}")
    void ofCreatesSku() {
        ProductSku sku = ProductSku.of(5L, "BLACK", "M", 10L);

        assertThat(sku.getProductVersionId()).isEqualTo(5L);
        assertThat(sku.getColor()).isEqualTo("BLACK");
        assertThat(sku.getSize()).isEqualTo("M");
        assertThat(sku.getSkuCode()).isEqualTo("SKU-10-BLACK-M");
        assertThat(sku.getCreatedAt()).isNotNull();
    }
}
