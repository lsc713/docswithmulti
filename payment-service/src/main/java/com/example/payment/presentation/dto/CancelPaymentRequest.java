package com.example.payment.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CancelPaymentRequest(
    @Size(max = 255) String cancelReason,
    @NotEmpty @Valid List<CancelItemRequest> cancelItems
) {}
