package com.example.order.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record InspectCancelRestoreRequest(
    @NotEmpty(message = "orderItemIds must not be empty")
    List<@NotNull(message = "orderItemId must not be null") Long> orderItemIds
) {}
