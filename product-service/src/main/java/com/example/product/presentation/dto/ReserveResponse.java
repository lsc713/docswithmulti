package com.example.product.presentation.dto;

public record ReserveResponse(boolean reserved) {
    public static ReserveResponse ok() {
        return new ReserveResponse(true);
    }
}
