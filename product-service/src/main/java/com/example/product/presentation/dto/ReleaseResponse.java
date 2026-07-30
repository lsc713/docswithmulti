package com.example.product.presentation.dto;

public record ReleaseResponse(boolean released) {
    public static ReleaseResponse ok() {
        return new ReleaseResponse(true);
    }
}
