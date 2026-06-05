package com.example.product.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 상품 버전 도메인 엔티티
 *
 * 상품의 이름/가격/속성 변경 시 새 버전을 생성한다.
 * Spring/JPA 어노테이션 없이 순수 Java로 작성한다.
 */
public class ProductVersion {

    private Long id;
    private long productId;
    private String name;
    private BigDecimal price;
    private BigDecimal discountPrice;
    private String attributes;
    private int version;
    private boolean isCurrent;
    private LocalDateTime createdAt;

    private ProductVersion(Long id, long productId, String name, BigDecimal price,
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

    /**
     * 최초 버전 생성 (version=1, isCurrent=true)
     */
    public static ProductVersion createFirst(long productId, String name, BigDecimal price,
                                             BigDecimal discountPrice, String attributes) {
        return new ProductVersion(null, productId, name, price, discountPrice, attributes,
                1, true, LocalDateTime.now());
    }

    /**
     * 다음 버전 생성 (previousVersion.version + 1, isCurrent=true)
     */
    public static ProductVersion createNext(long productId, String name, BigDecimal price,
                                            BigDecimal discountPrice, String attributes,
                                            ProductVersion previousVersion) {
        return new ProductVersion(null, productId, name, price, discountPrice, attributes,
                previousVersion.getVersion() + 1, true, LocalDateTime.now());
    }

    /**
     * DB 조회 후 재구성
     */
    public static ProductVersion reconstruct(Long id, long productId, String name, BigDecimal price,
                                             BigDecimal discountPrice, String attributes,
                                             int version, boolean isCurrent, LocalDateTime createdAt) {
        return new ProductVersion(id, productId, name, price, discountPrice, attributes,
                version, isCurrent, createdAt);
    }

    public void markNotCurrent() {
        this.isCurrent = false;
    }

    public Long getId() { return id; }
    public long getProductId() { return productId; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getDiscountPrice() { return discountPrice; }
    public String getAttributes() { return attributes; }
    public int getVersion() { return version; }
    public boolean isCurrent() { return isCurrent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
