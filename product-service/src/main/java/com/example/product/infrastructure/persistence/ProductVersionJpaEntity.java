package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ProductVersion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVersionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(precision = 12, scale = 2)
    private BigDecimal discountPrice;

    @Column(columnDefinition = "TEXT")
    private String attributes;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private boolean isCurrent;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private ProductVersionJpaEntity(Long id, long productId, String name, BigDecimal price,
                                    BigDecimal discountPrice, String attributes,
                                    int version, boolean isCurrent, LocalDateTime createdAt) {
        this.id = id;
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.discountPrice = discountPrice;
        this.attributes = attributes;
        this.version = version;
        this.isCurrent = isCurrent;
        this.createdAt = createdAt;
    }

    public static ProductVersionJpaEntity from(ProductVersion productVersion) {
        return new ProductVersionJpaEntity(
                productVersion.getId(),
                productVersion.getProductId(),
                productVersion.getName(),
                productVersion.getPrice(),
                productVersion.getDiscountPrice(),
                productVersion.getAttributes(),
                productVersion.getVersion(),
                productVersion.isCurrent(),
                productVersion.getCreatedAt()
        );
    }

    public ProductVersion toDomain() {
        return ProductVersion.reconstruct(id, productId, name, price, discountPrice, attributes,
                version, isCurrent, createdAt);
    }
}
