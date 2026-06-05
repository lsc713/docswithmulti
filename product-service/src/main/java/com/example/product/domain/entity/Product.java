package com.example.product.domain.entity;

import java.time.LocalDateTime;

/**
 * 상품 도메인 엔티티
 *
 * Spring/JPA 어노테이션 없이 순수 Java로 작성한다.
 */
public class Product {

    private Long id;
    private long merchantId;
    private long categoryId;
    private ProductStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Product(Long id, long merchantId, long categoryId, ProductStatus status,
                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.categoryId = categoryId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 신규 상품 생성 (ACTIVE 상태)
     */
    public static Product of(long merchantId, long categoryId) {
        LocalDateTime now = LocalDateTime.now();
        return new Product(null, merchantId, categoryId, ProductStatus.ACTIVE, now, now);
    }

    /**
     * DB 조회 후 재구성
     */
    public static Product reconstruct(Long id, long merchantId, long categoryId, ProductStatus status,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Product(id, merchantId, categoryId, status, createdAt, updatedAt);
    }

    public void activate() {
        this.status = ProductStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.status = ProductStatus.INACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public long getMerchantId() { return merchantId; }
    public long getCategoryId() { return categoryId; }
    public ProductStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
