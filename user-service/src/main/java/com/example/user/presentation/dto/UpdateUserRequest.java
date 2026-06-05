package com.example.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserRequest(@NotBlank String name, @NotBlank String phone) {}
