package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCategoryRequest(
        @NotBlank String name,
        Long parentId
) {
}
