package com.example.order.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(
    @Positive long userId,
    @NotEmpty @Valid List<Item> items
) {
    public record Item(
        @Positive long productId,
        @NotBlank String itemName,
        @Positive BigDecimal price
    ) {}
}
