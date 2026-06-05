package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductStock;

import java.util.Optional;

/**
 * 상품 재고 저장소 인터페이스
 *
 * infrastructure 레이어에서 JPA로 구현한다.
 */
public interface ProductStockRepository {

    ProductStock save(ProductStock stock);

    Optional<ProductStock> findBySkuId(long skuId);

    /**
     * 비관적 락으로 재고 조회 (동시 차감 시 데이터 정합성 보장)
     */
    Optional<ProductStock> findBySkuIdForUpdate(long skuId);
}
