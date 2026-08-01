package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductImage;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "product_image")
public class ProductImageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "s3_key", nullable = false, length = 512, unique = true)
    private String s3Key;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProductImageJpaEntity() {}

    public static ProductImageJpaEntity from(ProductImage img) {
        ProductImageJpaEntity e = new ProductImageJpaEntity();
        e.id = img.getId();
        e.productId = img.getProductId();
        e.s3Key = img.getS3Key();
        e.sortOrder = img.getSortOrder();
        e.createdAt = LocalDateTime.ofInstant(img.getCreatedAt(), ZoneOffset.UTC);
        return e;
    }

    void updateSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public ProductImage toDomain() {
        return ProductImage.reconstruct(id, productId, s3Key, sortOrder, createdAt.toInstant(ZoneOffset.UTC));
    }
}
