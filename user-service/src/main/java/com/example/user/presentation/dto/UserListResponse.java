package com.example.user.presentation.dto;

import java.util.List;

public record UserListResponse(
        List<UserSummary> content,
        int page,
        int size,
        long totalElements
) {
    public record UserSummary(
            long id,
            String email,
            String name,
            String role,
            String status,
            String createdAt
    ) {}
}
