package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductVersionTest {

    @Test
    @DisplayName("createFirst: version=1, isCurrent=true")
    void createFirst() {
        ProductVersion version = ProductVersion.createFirst(1L, "슬림핏 티셔츠",
                new BigDecimal("29000"), null, null);

        assertThat(version.getProductId()).isEqualTo(1L);
        assertThat(version.getName()).isEqualTo("슬림핏 티셔츠");
        assertThat(version.getPrice()).isEqualByComparingTo(new BigDecimal("29000"));
        assertThat(version.getDiscountPrice()).isNull();
        assertThat(version.getVersion()).isEqualTo(1);
        assertThat(version.isCurrent()).isTrue();
        assertThat(version.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("createNext: version이 이전 버전 +1, isCurrent=true")
    void createNext() {
        ProductVersion first = ProductVersion.createFirst(1L, "슬림핏 티셔츠",
                new BigDecimal("29000"), null, null);
        ProductVersion reconstructed = ProductVersion.reconstruct(
                1L, first.getProductId(), first.getName(), first.getPrice(),
                first.getDiscountPrice(), first.getAttributes(), first.getVersion(),
                first.isCurrent(), first.getCreatedAt());

        ProductVersion next = ProductVersion.createNext(1L, "슬림핏 티셔츠 v2",
                new BigDecimal("32000"), new BigDecimal("28000"), null, reconstructed);

        assertThat(next.getVersion()).isEqualTo(2);
        assertThat(next.isCurrent()).isTrue();
        assertThat(next.getName()).isEqualTo("슬림핏 티셔츠 v2");
    }

    @Test
    @DisplayName("markNotCurrent: isCurrent=false")
    void markNotCurrent() {
        ProductVersion version = ProductVersion.createFirst(1L, "슬림핏 티셔츠",
                new BigDecimal("29000"), null, null);

        version.markNotCurrent();

        assertThat(version.isCurrent()).isFalse();
    }
}
