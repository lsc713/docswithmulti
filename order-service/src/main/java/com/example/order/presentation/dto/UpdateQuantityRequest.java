package com.example.order.presentation.dto;

import jakarta.validation.constraints.Positive;

public record UpdateQuantityRequest(@Positive int quantity) {}
