package com.example.product.domain.entity;

import com.example.product.common.exception.domain.InsufficientStockException;

import java.time.LocalDateTime;

/**
 * 상품 재고 도메인 엔티티
 *
 * SKU 별 재고 수량을 관리한다.
 * Spring/JPA 어노테이션 없이 순수 Java로 작성한다.
 */
public class ProductStock {

    private Long id;
    private long skuId;
    private int quantity;
    private LocalDateTime updatedAt;

    private ProductStock(Long id, long skuId, int quantity, LocalDateTime updatedAt) {
        this.id = id;
        this.skuId = skuId;
        this.quantity = quantity;
        this.updatedAt = updatedAt;
    }

    /**
     * 신규 재고 생성
     */
    public static ProductStock of(long skuId, int quantity) {
        return new ProductStock(null, skuId, quantity, LocalDateTime.now());
    }

    /**
     * DB 조회 후 재구성
     */
    public static ProductStock reconstruct(Long id, long skuId, int quantity, LocalDateTime updatedAt) {
        return new ProductStock(id, skuId, quantity, updatedAt);
    }

    /**
     * 재고 차감
     *
     * @throws InsufficientStockException 재고 부족 시
     */
    public void deduct(int amount) {
        if (this.quantity < amount) {
            throw new InsufficientStockException(skuId, amount, this.quantity);
        }
        this.quantity -= amount;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 재고 복원 (취소 시 반환)
     */
    public void restore(int amount) {
        this.quantity += amount;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 재고 직접 설정 (관리자 수동 조정)
     */
    public void updateQuantity(int newQuantity) {
        this.quantity = newQuantity;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public long getSkuId() { return skuId; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
