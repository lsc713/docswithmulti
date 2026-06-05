package com.example.user.presentation.dto;

import com.example.user.domain.entity.User;

public record AdminUserResponse(Long id, String email, String name, String phone,
                                 String role, Long merchantId, String status) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getRole().name(), user.getMerchantId(), user.getStatus().name());
    }
}
