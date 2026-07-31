package com.example.user.application.usecase;

import com.example.user.presentation.dto.MeResponse;

public interface UserQueryUseCase {
    MeResponse getProfile(long userId);
}
