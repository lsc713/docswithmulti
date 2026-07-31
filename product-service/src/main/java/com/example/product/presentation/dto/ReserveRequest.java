package com.example.product.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** POST /v1/stock/reserve (spec §5). */
public record ReserveRequest(
        @NotBlank String paymentKey,
        @NotEmpty @Valid List<Item> items
) {
    public record Item(
            @NotNull Long skuId,
            @Positive int qty  // T-01-02: 음수/0 금지
    ) {}
}
