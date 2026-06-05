package com.example.product.domain.entity;

/**
 * 상품 상태
 */
public enum ProductStatus {

    ACTIVE,
    INACTIVE;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
