package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductSku;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "product_sku")
public class ProductSkuJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku_code", nullable = false, length = 64, unique = true)
    private String skuCode;

    @Column(name = "option_summary")
    private String optionSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductSkuJpaEntity() {}

    public static ProductSkuJpaEntity from(ProductSku s) {
        ProductSkuJpaEntity e = new ProductSkuJpaEntity();
        e.id = s.getId();
        e.productId = s.getProductId();
        e.skuCode = s.getSkuCode();
        e.optionSummary = s.getOptionSummary();
        e.createdAt = LocalDateTime.ofInstant(s.getCreatedAt(), ZoneOffset.UTC);
        e.updatedAt = LocalDateTime.ofInstant(s.getUpdatedAt(), ZoneOffset.UTC);
        return e;
    }

    public ProductSku toDomain() {
        return ProductSku.reconstruct(id, productId, skuCode, optionSummary,
                createdAt.toInstant(ZoneOffset.UTC), updatedAt.toInstant(ZoneOffset.UTC));
    }
}
