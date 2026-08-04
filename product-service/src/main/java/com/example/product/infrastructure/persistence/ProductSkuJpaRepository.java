package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductSkuJpaRepository extends JpaRepository<ProductSkuJpaEntity, Long> {

    /**
     * BROWSE-02: 상품의 SKU + availableQty 읽기 전용 조인 (product_stock 은 오직 여기서만 READ).
     * tryReserve/restore 쓰기 경로와 무관 (INV-01).
     */
    @Query(value = """
            SELECT s.id AS skuId, s.sku_code AS skuCode, s.option_summary AS optionSummary,
                   st.available_qty AS availableQty, s.price AS price
            FROM product_sku s
            JOIN product_stock st ON st.sku_id = s.id
            WHERE s.product_id = :productId
            ORDER BY s.id
            """, nativeQuery = true)
    List<SkuStockView> findSkuStockByProductId(@Param("productId") Long productId);

    /** 네이티브 인터페이스 프로젝션 — 컬럼 alias 가 getter 명과 매칭. */
    interface SkuStockView {
        Long getSkuId();
        String getSkuCode();
        String getOptionSummary();
        int getAvailableQty();
        long getPrice();
    }
}
