package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductStock;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStockJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private long skuId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private ProductStockJpaEntity(Long id, long skuId, int quantity, LocalDateTime updatedAt) {
        this.id = id;
        this.skuId = skuId;
        this.quantity = quantity;
        this.updatedAt = updatedAt;
    }

    public static ProductStockJpaEntity from(ProductStock stock) {
        return new ProductStockJpaEntity(
                stock.getId(),
                stock.getSkuId(),
                stock.getQuantity(),
                stock.getUpdatedAt()
        );
    }

    public ProductStock toDomain() {
        return ProductStock.reconstruct(id, skuId, quantity, updatedAt);
    }
}
