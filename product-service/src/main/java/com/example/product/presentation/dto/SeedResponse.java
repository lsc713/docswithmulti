package com.example.product.presentation.dto;

import com.example.product.application.service.CatalogService.SeedResult;

import java.util.List;

public record SeedResponse(Long productId, List<Sku> skus) {

    public record Sku(Long skuId, String skuCode) {}

    public static SeedResponse from(SeedResult r) {
        List<Sku> skus = r.skus().stream()
                .map(s -> new Sku(s.skuId(), s.skuCode()))
                .toList();
        return new SeedResponse(r.productId(), skus);
    }
}
