package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /v1/categories (spec §5). parentId 없으면 대분류. */
public record CreateCategoryRequest(
        Long parentId,
        @NotBlank String name
) {}
