package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.Product;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "product")
public class ProductJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // V4 이후 매핑 — Hibernate schema-validate 통과 조건(PLINK-02 "app boots").
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProductJpaEntity() {}

    public static ProductJpaEntity from(Product p) {
        ProductJpaEntity e = new ProductJpaEntity();
        e.id = p.getId();
        e.name = p.getName();
        e.categoryId = p.getCategoryId();
        e.createdAt = LocalDateTime.ofInstant(p.getCreatedAt(), ZoneOffset.UTC);
        e.updatedAt = LocalDateTime.ofInstant(p.getUpdatedAt(), ZoneOffset.UTC);
        return e;
    }

    public Product toDomain() {
        return Product.reconstruct(id, name, categoryId,
                createdAt.toInstant(ZoneOffset.UTC), updatedAt.toInstant(ZoneOffset.UTC));
    }
}
