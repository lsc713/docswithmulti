package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    @DisplayName("of()로 생성 시 ACTIVE 상태")
    void ofCreatesActive() {
        Product product = Product.of(100L, 10L);

        assertThat(product.getMerchantId()).isEqualTo(100L);
        assertThat(product.getCategoryId()).isEqualTo(10L);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.getCreatedAt()).isNotNull();
        assertThat(product.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("deactivate()로 INACTIVE 상태 전환")
    void deactivate() {
        Product product = Product.of(100L, 10L);
        product.deactivate();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    @Test
    @DisplayName("activate()로 ACTIVE 상태 전환")
    void activate() {
        Product product = Product.of(100L, 10L);
        product.deactivate();
        product.activate();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }
}
