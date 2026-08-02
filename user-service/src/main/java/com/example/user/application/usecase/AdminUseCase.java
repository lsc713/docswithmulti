package com.example.user.application.usecase;

import com.example.user.domain.entity.UserRole;

public interface AdminUseCase {
    record RoleChangeResult(long userId, UserRole role) {}

    RoleChangeResult changeRole(long userId, UserRole newRole);
}
