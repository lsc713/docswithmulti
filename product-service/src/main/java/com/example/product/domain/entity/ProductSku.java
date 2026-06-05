package com.example.product.domain.entity;

import java.time.LocalDateTime;

/**
 * 상품 SKU 도메인 엔티티
 *
 * color + size 조합으로 고유한 SKU를 식별한다.
 * Spring/JPA 어노테이션 없이 순수 Java로 작성한다.
 */
public class ProductSku {

    private Long id;
    private long productVersionId;
    private String color;
    private String size;
    private String skuCode;
    private LocalDateTime createdAt;

    private ProductSku(Long id, long productVersionId, String color, String size,
                       String skuCode, LocalDateTime createdAt) {
        this.id = id;
        this.productVersionId = productVersionId;
        this.color = color;
        this.size = size;
        this.skuCode = skuCode;
        this.createdAt = createdAt;
    }

    /**
     * 신규 SKU 생성
     * skuCode 형식: SKU-{productId}-{color}-{size}
     */
    public static ProductSku of(long productVersionId, String color, String size, long productId) {
        String skuCode = String.format("SKU-%d-%s-%s", productId, color, size);
        return new ProductSku(null, productVersionId, color, size, skuCode, LocalDateTime.now());
    }

    /**
     * DB 조회 후 재구성
     */
    public static ProductSku reconstruct(Long id, long productVersionId, String color, String size,
                                         String skuCode, LocalDateTime createdAt) {
        return new ProductSku(id, productVersionId, color, size, skuCode, createdAt);
    }

    public Long getId() { return id; }
    public long getProductVersionId() { return productVersionId; }
    public String getColor() { return color; }
    public String getSize() { return size; }
    public String getSkuCode() { return skuCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
