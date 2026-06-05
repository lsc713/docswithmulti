package com.example.user.presentation.dto;

import com.example.user.domain.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
    @Email @NotBlank String email,
    @NotBlank String password,
    @NotBlank String name,
    @NotBlank String phone,
    @NotNull UserRole role,
    Long merchantId
) {}
