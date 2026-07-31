package com.example.order.presentation.dto;

import com.example.order.application.usecase.VerifyOrderItemsUseCase;

public record VerifyOrderItemsResponse(long orderId) {
    public static VerifyOrderItemsResponse from(VerifyOrderItemsUseCase.Result result) {
        return new VerifyOrderItemsResponse(result.orderId());
    }
}
