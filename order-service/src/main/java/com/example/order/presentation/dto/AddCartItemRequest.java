package com.example.order.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record AddCartItemRequest(
    @NotNull Long skuId, @NotNull Long productId, @NotBlank String itemName,
    String optionSummary, @NotNull @PositiveOrZero Long unitPrice, @Positive int quantity) {}
