package com.example.payment.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreatePaymentRequest(
    @Positive long merchantId,
    @Positive long userId,
    @NotBlank String pgType,
    @Positive int cancelPeriodDays,
    @NotEmpty @Valid List<CreatePaymentItemRequest> items
) {}
