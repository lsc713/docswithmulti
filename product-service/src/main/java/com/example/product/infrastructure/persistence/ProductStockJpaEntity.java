package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductStock;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "product_stock")
public class ProductStockJpaEntity {

    @Id
    @Column(name = "sku_id")
    private Long skuId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductStockJpaEntity() {}

    public static ProductStockJpaEntity from(ProductStock s) {
        ProductStockJpaEntity e = new ProductStockJpaEntity();
        e.skuId = s.getSkuId();
        e.availableQty = s.getAvailableQty();
        e.updatedAt = LocalDateTime.ofInstant(s.getUpdatedAt(), ZoneOffset.UTC);
        return e;
    }
}
