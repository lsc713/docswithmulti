package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long merchantId;

    @Column(nullable = false)
    private long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private ProductJpaEntity(Long id, long merchantId, long categoryId, ProductStatus status,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.categoryId = categoryId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ProductJpaEntity from(Product product) {
        return new ProductJpaEntity(
                product.getId(),
                product.getMerchantId(),
                product.getCategoryId(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    public Product toDomain() {
        return Product.reconstruct(id, merchantId, categoryId, status, createdAt, updatedAt);
    }
}
