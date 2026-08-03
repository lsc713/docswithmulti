package com.example.product.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * POST /v1/products (spec §5). categoryId 는 leaf(level 3) — 검증은 CatalogService.
 * attributes/variantValueIds 는 하위호환 위해 선택적(null → 빈 목록). 변형 검증은 CatalogService(Task 3).
 */
public record SeedRequest(
        @NotBlank String name,
        @NotNull Long categoryId, // 누락 시 400 INVALID_REQUEST (leaf 여부는 서비스에서 PRODUCT_001)
        @Valid List<AttributeLine> attributes,      // 선언 속성 + 역할(변형/서술). 선택적.
        @NotEmpty @Valid List<SkuLine> skus
) {
    public record AttributeLine(
            @NotNull Long attributeId,
            boolean isVariant
    ) {}

    public record SkuLine(
            @NotBlank String skuCode,
            String optionSummary,
            @PositiveOrZero int initialStock,  // T-01-02: 음수 금지(available_qty 증가 유발)
            @NotNull @PositiveOrZero Long price,  // 누락/null → 400 (가격 필수, product owner 결정)
            List<Long> variantValueIds          // 변형값 조합. 선택적(null → 빈 목록).
    ) {}

    /** null → 빈 목록 정규화. */
    public List<AttributeLine> attributesOrEmpty() {
        return attributes == null ? List.of() : attributes;
    }
}
