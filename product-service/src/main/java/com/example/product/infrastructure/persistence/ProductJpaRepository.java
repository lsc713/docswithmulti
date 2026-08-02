package com.example.product.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    /**
     * BROWSE-01: 상품 카드 = id/name + 서브쿼리 MIN(price)(SKU 여러 개 중 최소가).
     * findSelfAndDescendantIds/findSkuStockByProductId 와 동일하게 네이티브 SQL(재귀/서브쿼리는 QueryDSL 로 표현 불가).
     */
    @Query(value = """
            SELECT p.id AS id, p.name AS name,
                   COALESCE((SELECT MIN(s.price) FROM product_sku s WHERE s.product_id = p.id), 0) AS minPrice,
                   (SELECT i.s3_key FROM product_image i WHERE i.product_id = p.id ORDER BY i.sort_order, i.id LIMIT 1) AS thumbnailKey
            FROM product p
            WHERE p.category_id IN (:categoryIds)
            ORDER BY p.created_at DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM product p WHERE p.category_id IN (:categoryIds)
            """,
            nativeQuery = true)
    Page<ProductCardView> findCardsByCategoryIds(@Param("categoryIds") List<Long> categoryIds, Pageable pageable);

    /** 네이티브 인터페이스 프로젝션 — 컬럼 alias 가 getter 명과 매칭. */
    interface ProductCardView {
        Long getId();
        String getName();
        Long getMinPrice();
        String getThumbnailKey();
    }
}
