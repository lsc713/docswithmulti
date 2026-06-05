package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSkuRequest(
        @NotBlank String color,
        @NotBlank String size,
        int initialQuantity
) {
}
