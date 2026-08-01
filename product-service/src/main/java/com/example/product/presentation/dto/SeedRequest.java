package com.example.product.presentation.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** POST /v1/products (spec §5). categoryId 는 leaf(level 3) — 검증은 CatalogService. */
public record SeedRequest(
        @NotBlank String name,
        @NotNull Long categoryId, // 누락 시 400 INVALID_REQUEST (leaf 여부는 서비스에서 PRODUCT_001)
        @NotEmpty @Valid List<SkuLine> skus
) {
    public record SkuLine(
            @NotBlank String skuCode,
            String optionSummary,
            @PositiveOrZero int initialStock,  // T-01-02: 음수 금지(available_qty 증가 유발)
            // Jackson 3(Spring Boot 4) FAIL_ON_NULL_FOR_PRIMITIVES 기본 true — price 누락 시 요청 전체가
            // 400(무본문)으로 깨지는 걸 막기 위해 명시적으로 0 기본값 허용(기존 price-미포함 클라이언트 호환).
            @PositiveOrZero @JsonSetter(nulls = Nulls.AS_EMPTY) long price
    ) {}
}
