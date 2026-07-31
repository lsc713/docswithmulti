package com.example.product.presentation.dto;

import com.example.product.application.service.ProductQueryService.ProductDetail;

import java.util.List;

/** GET /v1/products/{id} 응답 (BROWSE-02): 카테고리 경로(root→leaf) + SKU. */
public record ProductDetailResponse(Long id, String name, List<Category> category, List<Sku> skus) {

    public record Category(int level, Long id, String name) {}

    public record Sku(String skuCode, String optionSummary, int availableQty) {}

    public static ProductDetailResponse from(ProductDetail d) {
        List<Category> category = d.category().stream()
                .map(c -> new Category(c.level(), c.id(), c.name()))
                .toList();
        List<Sku> skus = d.skus().stream()
                .map(s -> new Sku(s.skuCode(), s.optionSummary(), s.availableQty()))
                .toList();
        return new ProductDetailResponse(d.id(), d.name(), category, skus);
    }
}
