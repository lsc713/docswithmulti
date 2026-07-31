package com.example.order.presentation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record VerifyOrderItemsRequest(
    @NotEmpty List<Long> orderItemIds
) {}
