package com.example.product.presentation.dto;

public record StockDeductResponse(
        long skuId,
        int remainingQuantity
) {
}
