package com.example.payment.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record CancelItemRequest(
    @NotNull Long paymentItemId
) {}
