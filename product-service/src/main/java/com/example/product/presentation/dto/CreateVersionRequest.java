package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateVersionRequest(
        @NotBlank String name,
        @NotNull BigDecimal price,
        BigDecimal discountPrice,
        String attributes
) {
}
