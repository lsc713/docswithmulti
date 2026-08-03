package com.example.product.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /v1/attributes/{id}/values (spec §5). 속성 값 생성. */
public record CreateAttributeValueRequest(@NotBlank String value) {}
