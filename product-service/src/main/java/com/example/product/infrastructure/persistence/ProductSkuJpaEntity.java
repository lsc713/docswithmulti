package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductSku;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_sku")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSkuJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long productVersionId;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private String size;

    @Column(nullable = false, unique = true)
    private String skuCode;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private ProductSkuJpaEntity(Long id, long productVersionId, String color, String size,
                                String skuCode, LocalDateTime createdAt) {
        this.id = id;
        this.productVersionId = productVersionId;
        this.color = color;
        this.size = size;
        this.skuCode = skuCode;
        this.createdAt = createdAt;
    }

    public static ProductSkuJpaEntity from(ProductSku sku) {
        return new ProductSkuJpaEntity(
                sku.getId(),
                sku.getProductVersionId(),
                sku.getColor(),
                sku.getSize(),
                sku.getSkuCode(),
                sku.getCreatedAt()
        );
    }

    public ProductSku toDomain() {
        return ProductSku.reconstruct(id, productVersionId, color, size, skuCode, createdAt);
    }
}
