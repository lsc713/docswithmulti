package com.example.user.presentation.dto;

import com.example.user.domain.entity.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull UserRole role) {}
