package com.example.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreatePaymentItemRequest(
    @Positive long orderItemId,
    @Positive long productId,
    @Positive long skuId,
    @Positive int quantity,
    @NotBlank String itemName,
    @Positive BigDecimal itemAmount
) {}
