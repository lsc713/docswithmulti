package com.example.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelRequestCreateRequest(@NotBlank String reason) {}
