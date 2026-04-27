package com.example.order.presentation.dto;

import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.domain.entity.OrderItem;

import java.util.List;

public record CreateOrderResponse(
    long orderId,
    String status,
    List<ItemResponse> items
) {
    public record ItemResponse(long orderItemId, String itemName) {}

    public static CreateOrderResponse from(CreateOrderUseCase.Result result) {
        List<ItemResponse> itemResponses = result.items().stream()
            .map(item -> new ItemResponse(item.getId(), item.getItemName()))
            .toList();
        return new CreateOrderResponse(
            result.order().getId(),
            result.order().getStatus().name(),
            itemResponses
        );
    }
}
