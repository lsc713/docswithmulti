package com.example.product.presentation.dto;

import com.example.product.application.service.ProductQueryService.ProductDetail;

import java.util.List;
import java.util.function.Function;

/** GET /v1/products/{id} 응답 (BROWSE-02): 카테고리 경로(root→leaf) + 이미지(id+url) + SKU. */
public record ProductDetailResponse(Long id, String name, List<Category> category, List<Image> images, List<Sku> skus) {

    public record Category(int level, Long id, String name) {}

    public record Image(Long id, String url) {}

    public record Sku(String skuCode, String optionSummary, int availableQty, long price) {}

    /** presign: imageKey → presigned GET URL. */
    public static ProductDetailResponse from(ProductDetail d, Function<String, String> presign) {
        List<Category> category = d.category().stream()
                .map(c -> new Category(c.level(), c.id(), c.name()))
                .toList();
        List<Image> images = d.images().stream()
                .map(ref -> new Image(ref.id(), presign.apply(ref.s3Key())))
                .toList();
        List<Sku> skus = d.skus().stream()
                .map(s -> new Sku(s.skuCode(), s.optionSummary(), s.availableQty(), s.price()))
                .toList();
        return new ProductDetailResponse(d.id(), d.name(), category, images, skus);
    }
}
