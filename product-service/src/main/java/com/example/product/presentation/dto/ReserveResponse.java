package com.example.product.presentation.dto;

import java.util.List;

public record ReserveResponse(boolean reserved, List<Item> items) {
    public static ReserveResponse ok(List<Item> items) {
        return new ReserveResponse(true, items);
    }

    public record Item(long skuId, long productId, long unitPrice, int quantity) {}
}
