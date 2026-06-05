package com.example.user.presentation.dto;

import com.example.user.domain.entity.User;

public record UserResponse(Long id, String email, String name, String phone,
                            String role, Long merchantId, String status) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getRole().name(), user.getMerchantId(), user.getStatus().name());
    }
}
