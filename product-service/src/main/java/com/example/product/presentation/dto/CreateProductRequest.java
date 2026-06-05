package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record CreateProductRequest(
        long categoryId,
        @NotBlank String name,
        @NotNull BigDecimal price,
        BigDecimal discountPrice,
        String attributes,
        @NotEmpty List<SkuInput> skus
) {
    public record SkuInput(String color, String size, int quantity) {}
}
