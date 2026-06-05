package com.example.product.infrastructure.security;

public record AuthenticatedUser(long userId, String role, Long merchantId) {
}
