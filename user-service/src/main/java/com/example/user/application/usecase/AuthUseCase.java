package com.example.user.application.usecase;

import com.example.user.domain.entity.UserRole;

public interface AuthUseCase {
    record SignupCommand(String email, String password, String name, String phone,
                         UserRole role, Long merchantId) {}
    record LoginCommand(String email, String password) {}
    record TokenResult(String accessToken, String refreshToken) {}

    TokenResult signup(SignupCommand command);
    TokenResult login(LoginCommand command);
    String refresh(String refreshToken);
    void logout(long userId);
}
