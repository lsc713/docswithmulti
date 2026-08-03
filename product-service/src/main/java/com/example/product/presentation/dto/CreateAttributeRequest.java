package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /v1/attributes (spec §5). 전역 속성 생성. */
public record CreateAttributeRequest(@NotBlank String name) {}
