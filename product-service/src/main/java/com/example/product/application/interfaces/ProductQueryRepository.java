package com.example.product.application.interfaces;

import com.example.product.domain.entity.Product;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;

/** 읽기 전용 조회 포트 (BROWSE-01/02). 쓰기 경로(ProductRepository, stock)와 분리. */
public interface ProductQueryRepository {

    Optional<Product> findProductById(Long id);

    /** SKU + availableQty (product_stock 읽기 전용 조인, INV-01). */
    List<SkuStock> findSkuStock(Long productId);

    /** BROWSE-01: 루트 + 모든 하위 카테고리 id (재귀 CTE). leaf 는 자기 자신만 반환. */
    List<Long> descendantCategoryIds(Long rootId);

    /** BROWSE-01: 상품 카드(최소가 + 썸네일) 최신순 페이징. thumbnailKey = 상품별 최소 sort_order 이미지 key(없으면 null). */
    Page<ProductCard> findCardsByCategoryIds(List<Long> categoryIds, int page, int size);

    record SkuStock(Long skuId, String skuCode, String optionSummary, int availableQty, long price) {}

    record ProductCard(Long id, String name, long minPrice, String thumbnailKey) {}
}
