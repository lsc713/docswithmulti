package com.example.product.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** POST /v1/products (spec §5). */
public record SeedRequest(
        @NotBlank String name,
        @NotEmpty @Valid List<SkuLine> skus
) {
    public record SkuLine(
            @NotBlank String skuCode,
            String optionSummary,
            @PositiveOrZero int initialStock  // T-01-02: 음수 금지(available_qty 증가 유발)
    ) {}
}
