package com.example.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelRejectRequest(@NotBlank String decisionReason) {}
