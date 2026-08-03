package com.example.user.application.usecase;

import com.example.user.presentation.dto.MeResponse;
import com.example.user.presentation.dto.UserListResponse;

public interface UserQueryUseCase {
    MeResponse getProfile(long userId);

    UserListResponse listUsers(int page, int size);
}
