package com.example.product.presentation.dto;

public record StockDeductRequest(
        long skuId,
        int quantity
) {
}
