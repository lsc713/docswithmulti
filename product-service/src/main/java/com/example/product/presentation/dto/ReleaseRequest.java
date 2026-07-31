package com.example.product.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** POST /v1/stock/release (spec §5, D-P1-4·D-P1-5). */
public record ReleaseRequest(
        @NotBlank String paymentKey,
        @NotEmpty @Valid List<Item> items
) {
    public record Item(
            @NotNull Long skuId,
            @Positive int qty
    ) {}
}
