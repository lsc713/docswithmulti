package com.example.product.application.interfaces;

import com.example.product.domain.entity.Product;

import java.util.List;
import java.util.Optional;

/** 읽기 전용 조회 포트 (BROWSE-01/02). 쓰기 경로(ProductRepository, stock)와 분리. */
public interface ProductQueryRepository {

    Optional<Product> findProductById(Long id);

    /** SKU + availableQty (product_stock 읽기 전용 조인, INV-01). */
    List<SkuStock> findSkuStock(Long productId);

    record SkuStock(String skuCode, String optionSummary, int availableQty) {}
}
