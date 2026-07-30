package com.example.user.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// D-P1-2: role/merchantId 필드 제거 — 클라가 신뢰경계에서 특권 필드 자기지정 불가.
// 서버가 UserRole.USER / merchantId=null 강제(AuthController.signup).
public record SignupRequest(
    @Email @NotBlank String email,
    @NotBlank String password,
    @NotBlank String name,
    @NotBlank String phone
) {}
