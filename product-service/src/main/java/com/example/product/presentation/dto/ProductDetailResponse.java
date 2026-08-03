package com.example.product.presentation.dto;

import com.example.product.application.service.ProductQueryService.ProductDetail;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * GET /v1/products/{id} 응답 (BROWSE-02 + VQUERY-01): 카테고리 경로(root→leaf) + 이미지 + SKU
 * + 구조화 변형(variantOptions + 각 SKU variant). 기존 필드는 그대로 병존(하위호환).
 */
public record ProductDetailResponse(Long id, String name, List<Category> category, List<Image> images,
                                    List<Sku> skus, List<VariantOption> variantOptions,
                                    List<VariantOption> specs) {

    public record Category(int level, Long id, String name) {}

    public record Image(Long id, String url) {}

    public record Sku(String skuCode, String optionSummary, int availableQty, long price,
                      Map<String, String> variant) {}

    public record VariantOption(String attribute, List<String> values) {}

    /** presign: ImageRef.s3Key → presigned GET URL, id는 그대로 전달. */
    public static ProductDetailResponse from(ProductDetail d, Function<String, String> presign) {
        List<Category> category = d.category().stream()
                .map(c -> new Category(c.level(), c.id(), c.name()))
                .toList();
        List<Image> images = d.images().stream()
                .map(ref -> new Image(ref.id(), presign.apply(ref.s3Key())))
                .toList();
        List<Sku> skus = d.skus().stream()
                .map(s -> new Sku(s.skuCode(), s.optionSummary(), s.availableQty(), s.price(), s.variant()))
                .toList();
        List<VariantOption> variantOptions = d.variantOptions().stream()
                .map(o -> new VariantOption(o.attribute(), o.values()))
                .toList();
        List<VariantOption> specs = d.specs().stream()
                .map(o -> new VariantOption(o.attribute(), o.values()))
                .toList();
        return new ProductDetailResponse(d.id(), d.name(), category, images, skus, variantOptions, specs);
    }
}
