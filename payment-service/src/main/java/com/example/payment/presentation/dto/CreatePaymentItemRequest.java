package com.example.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreatePaymentItemRequest(
    @Positive long orderItemId,
    @Positive long productId,
    @NotBlank String itemName,
    @Positive BigDecimal itemAmount,
    @NotNull Long skuId,
    @Positive int quantity
) {}
