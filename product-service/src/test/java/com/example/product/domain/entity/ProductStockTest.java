package com.example.product.domain.entity;

import com.example.product.common.exception.domain.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductStockTest {

    @Test
    @DisplayName("of()로 재고 생성")
    void ofCreatesStock() {
        ProductStock stock = ProductStock.of(1L, 100);

        assertThat(stock.getSkuId()).isEqualTo(1L);
        assertThat(stock.getQuantity()).isEqualTo(100);
        assertThat(stock.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("deduct() — 재고 차감")
    void deductReducesQuantity() {
        ProductStock stock = ProductStock.of(1L, 100);
        stock.deduct(30);

        assertThat(stock.getQuantity()).isEqualTo(70);
    }

    @Test
    @DisplayName("deduct() — 재고 부족 시 InsufficientStockException")
    void deductThrowsWhenInsufficient() {
        ProductStock stock = ProductStock.of(1L, 10);

        assertThatThrownBy(() -> stock.deduct(20))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(ex -> {
                    InsufficientStockException ise = (InsufficientStockException) ex;
                    assertThat(ise.getSkuId()).isEqualTo(1L);
                    assertThat(ise.getRequested()).isEqualTo(20);
                    assertThat(ise.getAvailable()).isEqualTo(10);
                });
    }

    @Test
    @DisplayName("restore() — 재고 복원")
    void restoreIncreasesQuantity() {
        ProductStock stock = ProductStock.of(1L, 50);
        stock.restore(25);

        assertThat(stock.getQuantity()).isEqualTo(75);
    }

    @Test
    @DisplayName("updateQuantity() — 재고 직접 설정")
    void updateQuantitySetsDirectly() {
        ProductStock stock = ProductStock.of(1L, 50);
        stock.updateQuantity(200);

        assertThat(stock.getQuantity()).isEqualTo(200);
    }
}
