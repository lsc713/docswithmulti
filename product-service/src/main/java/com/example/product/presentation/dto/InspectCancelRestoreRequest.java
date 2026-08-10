package com.example.product.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record InspectCancelRestoreRequest(
    @NotBlank(message = "paymentKey must not be blank") String paymentKey,
    @NotEmpty(message = "items must not be empty") List<@Valid ItemRequest> items
) {
    public record ItemRequest(
        @Positive(message = "skuId must be positive") long skuId,
        @Positive(message = "quantity must be positive") int quantity
    ) {}
}
